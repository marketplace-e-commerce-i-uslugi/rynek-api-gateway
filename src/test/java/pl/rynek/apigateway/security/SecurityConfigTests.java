package pl.rynek.apigateway.security;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Serwisy docelowe wskazują na zamknięty port: przejście przez security kończy się 503 z gateway,
 * zablokowanie przez security — 401.
 */
class SecurityConfigTests {

    static final String DOWNSTREAM_DOWN = "http://localhost:1";
    static final KeyPair KEYS = generateKeys();

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
            "rynek.security.jwt.enforce=true",
            "IDENTITY_SERVICE_URL=" + DOWNSTREAM_DOWN,
            "CATALOG_SERVICE_URL=" + DOWNSTREAM_DOWN,
            "ENGAGEMENT_SERVICE_URL=" + DOWNSTREAM_DOWN,
            "spring.data.redis.port=1"})
    class Enforced {

        @LocalServerPort
        int port;
        WebTestClient client;

        @TestConfiguration
        static class TestKeys {
            @Bean
            @Primary
            ReactiveJwtDecoder testJwtDecoder() {
                NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withPublicKey((RSAPublicKey) KEYS.getPublic()).build();
                decoder.setJwtValidator(SecurityConfig.validator("rynek-identity"));
                return decoder;
            }
        }

        @BeforeEach
        void setUp() {
            client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        }

        @Test
        void protectedEndpointWithoutTokenIs401ProblemJson() {
            client.get().uri("/api/v1/users/me").exchange()
                    .expectStatus().isUnauthorized()
                    .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                    .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectBody().jsonPath("$.status").isEqualTo(401);
        }

        @Test
        void protectedEndpointWithValidTokenIsRouted() {
            client.get().uri("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token("rynek-identity", 300))
                    .exchange().expectStatus().isEqualTo(503);
        }

        @Test
        void expiredTokenIs401() {
            client.get().uri("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token("rynek-identity", -300))
                    .exchange().expectStatus().isUnauthorized();
        }

        @Test
        void tokenFromOtherIssuerIs401() {
            client.get().uri("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token("ktos-inny", 300))
                    .exchange().expectStatus().isUnauthorized();
        }

        @Test
        void publicEndpointsPassWithoutToken() {
            client.get().uri("/api/v1/offers/o1").exchange().expectStatus().isEqualTo(503);
            client.get().uri("/api/v1/offers/o1/questions").exchange().expectStatus().isEqualTo(503);
            client.post().uri("/api/v1/auth/login").exchange().expectStatus().isEqualTo(503);
        }

        @Test
        void invalidOrExpiredTokenIsIgnoredOnPublicEndpoints() {
            // Wygasły token nie może blokować publicznych ekranów ani jego własnego odnowienia (POST /auth/refresh).
            client.get().uri("/api/v1/offers/o1").header(HttpHeaders.AUTHORIZATION, "Bearer zepsuty.token.xyz")
                    .exchange().expectStatus().isEqualTo(503);
            client.post().uri("/api/v1/auth/refresh").header(HttpHeaders.AUTHORIZATION, "Bearer " + token("rynek-identity", -300))
                    .exchange().expectStatus().isEqualTo(503);
        }

        @Test
        void invalidTokenOnProtectedEndpointIs401() {
            client.get().uri("/api/v1/offers/mine").header(HttpHeaders.AUTHORIZATION, "Bearer zepsuty.token.xyz")
                    .exchange().expectStatus().isUnauthorized();
        }

        @Test
        void sameShapeProtectedPathsNeedToken() {
            client.get().uri("/api/v1/offers/mine").exchange().expectStatus().isUnauthorized();
            client.post().uri("/api/v1/offers/o1/questions").exchange().expectStatus().isUnauthorized();
        }

        @Test
        void corsPreflightFromFrontendIsAllowed() {
            client.options().uri("/api/v1/users/me")
                    .header(HttpHeaders.ORIGIN, "http://localhost:4200")
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PATCH")
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type")
                    .exchange()
                    .expectStatus().isOk()
                    .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:4200");
        }

        @Test
        void corsPreflightFromUnknownOriginIsRejected() {
            client.options().uri("/api/v1/users/me")
                    .header(HttpHeaders.ORIGIN, "https://evil.example")
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                    .exchange()
                    .expectStatus().isForbidden();
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
            "IDENTITY_SERVICE_URL=" + DOWNSTREAM_DOWN,
            "spring.data.redis.port=1"})
    class NotEnforcedByDefault {

        @LocalServerPort
        int port;

        @Test
        void protectedEndpointPassesWithoutToken() {
            WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build()
                    .get().uri("/api/v1/users/me").exchange().expectStatus().isEqualTo(503);
        }
    }

    static String token(String issuer, long expiresInSeconds) {
        try {
            Instant now = Instant.now();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .subject("u1")
                    .claim("roles", List.of("BUYER"))
                    .issueTime(Date.from(now.minusSeconds(600)))
                    .expirationTime(Date.from(now.plusSeconds(expiresInSeconds)))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
            jwt.sign(new RSASSASigner((RSAPrivateKey) KEYS.getPrivate()));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static KeyPair generateKeys() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
