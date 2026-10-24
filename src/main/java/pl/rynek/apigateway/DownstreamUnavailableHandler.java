package pl.rynek.apigateway;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

/**
 * Gdy serwis docelowy nie przyjmuje połączeń, gateway odpowiada 503 (RFC 7807) zamiast domyślnego 500.
 * Dzięki temu klient odróżnia „serwis nie działa” od błędu w samym serwisie (frontend przechodzi wtedy na dane przykładowe).
 */
@Component
@Order(-2)
public class DownstreamUnavailableHandler implements WebExceptionHandler {

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        ServerHttpResponse response = exchange.getResponse();
        if (!causedByConnectionRefused(ex) || response.isCommitted()) {
            return Mono.error(ex);
        }
        String path = exchange.getRequest().getPath().value().replace("\"", "");
        String body = "{\"title\":\"Service Unavailable\",\"status\":503,\"detail\":\"Serwis docelowy nie odpowiada\",\"instance\":\"" + path + "\"}";
        response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
    }

    private static boolean causedByConnectionRefused(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof ConnectException) {
                return true;
            }
        }
        return false;
    }
}
