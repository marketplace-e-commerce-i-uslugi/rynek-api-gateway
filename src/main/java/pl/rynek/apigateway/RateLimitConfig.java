package pl.rynek.apigateway;

import java.net.InetSocketAddress;
import java.security.Principal;

import io.lettuce.core.ClientOptions;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * Klucz limitu zapytań ({@code RequestRateLimiter} + Redis, zob. {@code default-filters} w application.yml):
 * zalogowany użytkownik ({@code sub} z JWT) albo adres IP klienta.
 * Gdy Redis nie działa, {@code RedisRateLimiter} przepuszcza żądania (limity są wtedy wyłączone, routing działa).
 */
@Configuration
public class RateLimitConfig {

    /**
     * Bez tego Lettuce po zerwaniu połączenia kolejkuje komendy do ponownego połączenia,
     * a każde żądanie czeka na timeout komendy. Odrzucenie od razu = limiter przepuszcza żądanie bez opóźnienia.
     */
    @Bean
    LettuceClientOptionsBuilderCustomizer rejectCommandsWhenRedisDisconnected() {
        return options -> options.disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
    }

    @Bean
    KeyResolver clientKeyResolver() {
        return exchange -> exchange.getPrincipal()
                .map(Principal::getName)
                .map(name -> "user:" + name)
                .switchIfEmpty(Mono.fromSupplier(() -> {
                    InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
                    String ip = remote != null && remote.getAddress() != null ? remote.getAddress().getHostAddress() : "unknown";
                    return "ip:" + ip;
                }));
    }
}
