package pl.rynek.apigateway.security;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.server.authentication.ServerBearerTokenAuthenticationConverter;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import pl.rynek.apigateway.ProblemResponses;
import reactor.core.publisher.Mono;

/**
 * Uwierzytelnianie i CORS w gateway.
 * <p>
 * {@code rynek.security.jwt.enforce=true}: ścieżki spoza {@link PublicEndpoints} wymagają JWT (RS256) wystawionego
 * przez identity-service; klucze publiczne gateway pobiera z JWKS identity. Brak lub zły token daje 401 problem+json.
 * <p>
 * {@code enforce=false} (domyślnie, dopóki identity nie wystawia tokenów): gateway przepuszcza wszystko, a nagłówek
 * {@code Authorization} trafia do serwisów bez zmian. Dzięki temu frontend w trybie {@code auto} dalej dostaje 501
 * i przechodzi na dane przykładowe.
 */
@Configuration
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    @ConditionalOnProperty(name = "rynek.security.jwt.enforce", havingValue = "true")
    SecurityWebFilterChain enforcingFilterChain(ServerHttpSecurity http) {
        return common(http)
                .authorizeExchange(exchanges -> {
                    exchanges.pathMatchers(HttpMethod.OPTIONS).permitAll();
                    PublicEndpoints.PROTECTED_EXCEPTIONS.forEach(rule -> exchanges.pathMatchers(rule.method(), rule.pattern()).authenticated());
                    PublicEndpoints.RULES.forEach(rule -> exchanges.pathMatchers(rule.method(), rule.pattern()).permitAll());
                    exchanges.anyExchange().authenticated();
                })
                .oauth2ResourceServer(oauth2 -> oauth2
                        .bearerTokenConverter(ignoreTokenOnPublicEndpoints())
                        .jwt(jwt -> {
                        })
                        .authenticationEntryPoint(unauthorized()))
                .exceptionHandling(handling -> handling.authenticationEntryPoint(unauthorized()))
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "rynek.security.jwt.enforce", havingValue = "false", matchIfMissing = true)
    SecurityWebFilterChain permissiveFilterChain(ServerHttpSecurity http) {
        log.warn("Walidacja JWT w gateway jest WYŁĄCZONA (rynek.security.jwt.enforce=false). Włącz ją, gdy identity-service wystawia tokeny.");
        return common(http)
                .authorizeExchange(exchanges -> exchanges.anyExchange().permitAll())
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "rynek.security.jwt.enforce", havingValue = "true")
    ReactiveJwtDecoder jwtDecoder(@Value("${rynek.security.jwt.jwk-set-uri}") String jwkSetUri,
                                  @Value("${rynek.security.jwt.issuer}") String issuer) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(validator(issuer));
        return decoder;
    }

    /** Ważność ({@code exp}, {@code nbf}), wystawca ({@code iss}) i obecność {@code sub}. */
    static OAuth2TokenValidator<Jwt> validator(String issuer) {
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new JwtClaimValidator<String>(JwtClaimNames.SUB, sub -> sub != null && !sub.isBlank()));
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${rynek.cors.allowed-origins}") List<String> allowedOrigins) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(allowedOrigins);
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, HttpHeaders.ACCEPT,
                HttpHeaders.ACCEPT_LANGUAGE, "Idempotency-Key"));
        cors.setExposedHeaders(List.of(HttpHeaders.LOCATION, HttpHeaders.RETRY_AFTER, "X-RateLimit-Remaining"));
        cors.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }

    private static ServerHttpSecurity common(ServerHttpSecurity http) {
        // Bezstanowe API z tokenem Bearer: bez sesji, ciasteczek i formularzy, więc bez CSRF.
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .cors(cors -> {
                });
    }

    /**
     * Na endpointach publicznych nagłówek {@code Authorization} nie jest sprawdzany: wygasły token nie może dać 401
     * na stronie głównej ani na {@code POST /auth/refresh}, który służy właśnie do jego odnowienia.
     * Nagłówek i tak trafia do serwisu, który może go użyć (np. do personalizacji).
     */
    private static ServerAuthenticationConverter ignoreTokenOnPublicEndpoints() {
        ServerBearerTokenAuthenticationConverter bearer = new ServerBearerTokenAuthenticationConverter();
        ServerWebExchangeMatcher publicEndpoints = PublicEndpoints.matcher();
        return exchange -> publicEndpoints.matches(exchange)
                .flatMap(match -> match.isMatch() ? Mono.empty() : bearer.convert(exchange));
    }

    private static ServerAuthenticationEntryPoint unauthorized() {
        return (exchange, ex) -> {
            exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            return ProblemResponses.write(exchange, HttpStatus.UNAUTHORIZED, "Zaloguj się, aby kontynuować.");
        };
    }
}
