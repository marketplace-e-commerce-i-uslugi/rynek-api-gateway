package pl.rynek.apigateway;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

/**
 * Błędy zgłaszane przez sam gateway (np. 404 dla ścieżki bez trasy, w tym {@code /internal/**})
 * w formacie RFC 7807, tak jak 401 i 503, zamiast domyślnego JSON-a Spring Boot.
 * Odpowiedzi serwisów docelowych przechodzą bez zmian.
 */
@Component
@Order(-2)
public class ResponseStatusProblemHandler implements WebExceptionHandler {

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (!(ex instanceof ResponseStatusException statusEx) || exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }
        HttpStatus status = HttpStatus.resolve(statusEx.getStatusCode().value());
        if (status == null) {
            return Mono.error(ex);
        }
        exchange.getResponse().getHeaders().addAll(statusEx.getHeaders());
        String detail = status == HttpStatus.NOT_FOUND ? "Nie znaleziono zasobu" : status.getReasonPhrase();
        return ProblemResponses.write(exchange, status, detail);
    }
}
