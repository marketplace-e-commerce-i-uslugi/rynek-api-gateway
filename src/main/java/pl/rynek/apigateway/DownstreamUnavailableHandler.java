package pl.rynek.apigateway;

import java.net.ConnectException;
import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;

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

    private static final Logger log = LoggerFactory.getLogger(DownstreamUnavailableHandler.class);

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        ConnectException cause = connectException(ex);
        if (cause == null || exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }
        // Bez tego 503 nie zostawia śladu i nie wiadomo, który serwis nie przyjął połączenia.
        URI target = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
        log.warn("503: {} {} -> {} nie przyjmuje połączeń ({})", exchange.getRequest().getMethod(),
                exchange.getRequest().getPath().value(), target, cause.toString());
        return ProblemResponses.write(exchange, HttpStatus.SERVICE_UNAVAILABLE, "Serwis docelowy nie odpowiada");
    }

    private static ConnectException connectException(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof ConnectException connect) {
                return connect;
            }
        }
        return null;
    }
}
