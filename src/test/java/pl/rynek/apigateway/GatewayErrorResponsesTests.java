package pl.rynek.apigateway;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Redis wskazuje na „czarną dziurę”: port przyjmuje połączenia, ale nigdy nie odpowiada (Redis zawieszony albo
 * zerwane połączenie). Gateway ma wtedy dalej kierować ruch bez czekania na domyślny 60-sekundowy timeout Lettuce.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "FEED_SERVICE_URL=http://localhost:1"})
class GatewayErrorResponsesTests {

    static final BlackHole REDIS = new BlackHole();

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.port", REDIS::port);
    }

    @AfterAll
    static void closeRedis() throws IOException {
        REDIS.close();
    }

    @LocalServerPort
    int port;
    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(5)).build();
    }

    @Test
    void unresponsiveRedisDoesNotBlockRouting() {
        for (int i = 0; i < 3; i++) {
            client.get().uri("/api/v1/feed/home").exchange().expectStatus().isEqualTo(503);
        }
    }

    @Test
    void pathWithoutRouteIs404ProblemJson() {
        client.get().uri("/api/v1/nie-ma-takiej-sciezki").exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(404)
                .jsonPath("$.instance").isEqualTo("/api/v1/nie-ma-takiej-sciezki");
    }

    @Test
    void internalPathIs404ProblemJson() {
        client.get().uri("/internal/auth/jwks").exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON);
    }

    /** Serwer TCP, który przyjmuje połączenia i nic nie odsyła. */
    static final class BlackHole implements AutoCloseable {

        private final ServerSocket server;
        private final List<Socket> accepted = new CopyOnWriteArrayList<>();

        BlackHole() {
            try {
                server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            Thread acceptor = new Thread(() -> {
                while (!server.isClosed()) {
                    try {
                        accepted.add(server.accept());
                    } catch (IOException ignored) {
                        // zamknięty serwer kończy pętlę
                    }
                }
            }, "redis-black-hole");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        int port() {
            return server.getLocalPort();
        }

        @Override
        public void close() throws IOException {
            server.close();
            for (Socket socket : accepted) {
                socket.close();
            }
        }
    }
}
