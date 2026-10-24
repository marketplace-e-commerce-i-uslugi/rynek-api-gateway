package pl.rynek.apigateway;

import java.net.ConnectException;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
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
        if (!causedByConnectionRefused(ex) || exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }
        return ProblemResponses.write(exchange, HttpStatus.SERVICE_UNAVAILABLE, "Serwis docelowy nie odpowiada");
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
