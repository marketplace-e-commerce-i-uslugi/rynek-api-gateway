package pl.rynek.apigateway.security;

import java.util.List;

import org.springframework.http.HttpMethod;
import org.springframework.security.web.server.util.matcher.AndServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.NegatedServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.OrServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;

/**
 * Endpointy z kolumną „Dostęp” = {@code pub} w README serwisów. Gateway przepuszcza je bez tokenu;
 * wszystkie pozostałe ścieżki {@code /api/v1/**} wymagają ważnego JWT.
 * Gateway sprawdza tylko uwierzytelnienie. Role ({@code seller}, {@code admin}) i własność zasobu sprawdza serwis.
 * Przy dodaniu publicznego endpointu w serwisie dopisz go tutaj.
 */
final class PublicEndpoints {

    record Rule(HttpMethod method, String pattern) {
    }

    /** Ścieżki chronione, które pasują do wzorca publicznego poniżej (np. {@code /offers/mine} a {@code /offers/*}). Sprawdzane najpierw. */
    static final List<Rule> PROTECTED_EXCEPTIONS = List.of(
            new Rule(HttpMethod.GET, "/api/v1/offers/mine"));

    static final List<Rule> RULES = List.of(
            // identity
            new Rule(HttpMethod.POST, "/api/v1/auth/register"),
            new Rule(HttpMethod.POST, "/api/v1/auth/login"),
            new Rule(HttpMethod.POST, "/api/v1/auth/oauth/google"),
            new Rule(HttpMethod.POST, "/api/v1/auth/refresh"),
            new Rule(HttpMethod.POST, "/api/v1/auth/password/forgot"),
            new Rule(HttpMethod.POST, "/api/v1/auth/password/reset"),
            // channel
            new Rule(HttpMethod.GET, "/api/v1/channels/*"),
            new Rule(HttpMethod.GET, "/api/v1/channels/by-handle/*"),
            new Rule(HttpMethod.GET, "/api/v1/channels/*/about"),
            // catalog
            new Rule(HttpMethod.GET, "/api/v1/categories"),
            new Rule(HttpMethod.GET, "/api/v1/categories/*/attributes"),
            new Rule(HttpMethod.GET, "/api/v1/offers"),
            new Rule(HttpMethod.GET, "/api/v1/offers/*"),
            // media
            new Rule(HttpMethod.GET, "/api/v1/media/*"),
            // tiktok-integration
            new Rule(HttpMethod.GET, "/api/v1/tiktok/callback"),
            new Rule(HttpMethod.POST, "/api/v1/tiktok/webhooks"),
            new Rule(HttpMethod.GET, "/api/v1/videos/*"),
            new Rule(HttpMethod.GET, "/api/v1/offers/*/video"),
            new Rule(HttpMethod.GET, "/api/v1/channels/*/videos"),
            // feed
            new Rule(HttpMethod.GET, "/api/v1/feed/home"),
            new Rule(HttpMethod.GET, "/api/v1/feed/chips"),
            new Rule(HttpMethod.GET, "/api/v1/feed/shorts"),
            new Rule(HttpMethod.GET, "/api/v1/feed/related"),
            new Rule(HttpMethod.POST, "/api/v1/feed/events"),
            // search
            new Rule(HttpMethod.GET, "/api/v1/search/offers"),
            new Rule(HttpMethod.GET, "/api/v1/search/suggest"),
            new Rule(HttpMethod.GET, "/api/v1/search/channels"),
            new Rule(HttpMethod.GET, "/api/v1/search/locations"),
            // engagement
            new Rule(HttpMethod.GET, "/api/v1/offers/*/questions"),
            new Rule(HttpMethod.GET, "/api/v1/channels/*/reviews"),
            new Rule(HttpMethod.GET, "/api/v1/channels/*/rating-summary"),
            // payment, shipping: webhooki dostawców (serwis weryfikuje podpis)
            new Rule(HttpMethod.POST, "/api/v1/payments/webhooks/*"),
            new Rule(HttpMethod.GET, "/api/v1/shipping/methods"),
            new Rule(HttpMethod.GET, "/api/v1/shipping/pickup-points"),
            new Rule(HttpMethod.POST, "/api/v1/shipping/webhooks/*"),
            // booking
            new Rule(HttpMethod.GET, "/api/v1/services/*/slots"),
            new Rule(HttpMethod.GET, "/api/v1/services/*/next-slot"),
            // analytics
            new Rule(HttpMethod.POST, "/api/v1/analytics/events"),
            // chat: przeglądarka nie wysyła nagłówka Authorization przy WebSocket; token idzie w ramce STOMP CONNECT
            new Rule(null, "/ws/chat/**"),
            new Rule(HttpMethod.GET, "/actuator/health/**"),
            new Rule(HttpMethod.GET, "/actuator/info"));

    private PublicEndpoints() {
    }

    /** Publiczne według {@link #RULES}, z wyłączeniem {@link #PROTECTED_EXCEPTIONS}; te same reguły co w autoryzacji. */
    static ServerWebExchangeMatcher matcher() {
        return new AndServerWebExchangeMatcher(
                new OrServerWebExchangeMatcher(RULES.stream().map(PublicEndpoints::toMatcher).toList()),
                new NegatedServerWebExchangeMatcher(
                        new OrServerWebExchangeMatcher(PROTECTED_EXCEPTIONS.stream().map(PublicEndpoints::toMatcher).toList())));
    }

    private static ServerWebExchangeMatcher toMatcher(Rule rule) {
        return rule.method() == null
                ? new PathPatternParserServerWebExchangeMatcher(rule.pattern())
                : new PathPatternParserServerWebExchangeMatcher(rule.pattern(), rule.method());
    }
}
