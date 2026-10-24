package pl.rynek.apigateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;

/**
 * Pierwsza pasująca trasa (w kolejności {@code order}) decyduje o serwisie docelowym.
 * Wyjątki routingu (README) muszą wygrywać z trasami po prefiksie.
 */
@SpringBootTest
class RoutingTests {

    @Autowired
    RouteLocator routeLocator;

    @ParameterizedTest
    @CsvSource({
            "/api/v1/offers/o1/video,               tiktok-integration-offer-video",
            "/api/v1/channels/c1/videos,            tiktok-integration-channel-videos",
            "/api/v1/offers/o1/questions,           engagement-offers",
            "/api/v1/offers/o1/like,                engagement-offers",
            "/api/v1/channels/c1/reviews,           engagement-channels",
            "/api/v1/channels/c1/rating-summary,    engagement-channels",
            "/api/v1/orders/o1/review,              engagement-orders",
            "/api/v1/bookings/b1/review,            engagement-bookings",
            "/api/v1/users/me/following,            channel-following",
            "/api/v1/users/me,                      identity",
            "/api/v1/auth/login,                    identity",
            "/api/v1/channels/c1,                   channel",
            "/api/v1/offers/o1,                     catalog",
            "/api/v1/offers/o1/quality-score,       catalog",
            "/api/v1/orders/o1,                     order",
            "/api/v1/bookings/b1,                   booking",
            "/api/v1/seller/balance,                payment",
            "/api/v1/seller/payout-account,         payment",
            "/api/v1/seller/analytics/overview,     analytics",
            "/ws/chat,                              chat-ws",
    })
    void routesPathToOwningService(String path, String expectedRouteId) {
        assertThat(firstMatchingRoute(path)).isEqualTo(expectedRouteId);
    }

    @ParameterizedTest
    @CsvSource({"/internal/users/u1", "/internal/offers/o1/price-and-stock"})
    void internalPathsAreNotRouted(String path) {
        assertThat(firstMatchingRoute(path)).isNull();
    }

    private String firstMatchingRoute(String path) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
        return routeLocator.getRoutes()
                .sort((a, b) -> Integer.compare(a.getOrder(), b.getOrder()))
                .concatMap(route -> Flux.from(route.getPredicate().apply(exchange)).filter(Boolean::booleanValue).map(matched -> route))
                .next()
                .map(Route::getId)
                .block();
    }
}
