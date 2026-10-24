# rynek-api-gateway

`api-gateway` (Spring Cloud Gateway)

> Status: **szkielet**. Routing z §2 jest skonfigurowany w `application.yml`; walidacja JWT, limity i CORS nie są jeszcze zaimplementowane.

## Uruchomienie

```bash
./mvnw spring-boot:run   # port 8080, zmienna PORT
```

Adresy serwisów docelowych: zmienne `<SERWIS>_SERVICE_URL` (np. `CATALOG_SERVICE_URL`), domyślnie `http://localhost:<port serwisu>`. Ścieżki `/internal/**` nie są wystawiane.

Gdy serwis docelowy nie przyjmuje połączeń, gateway odpowiada `503 Service Unavailable` w formacie RFC 7807 (`DownstreamUnavailableHandler`) zamiast domyślnego 500. Frontend w trybie `auto` przechodzi wtedy na dane przykładowe.

## Zakres i endpointy

_Skopiowane z `rynek-docs/architektura/rynek-endpointy-mikroserwisy.md`. Od teraz to repozytorium jest właścicielem tej specyfikacji._

Brak logiki biznesowej. Odpowiada za routing, walidację JWT, limity zapytań, CORS i agregację nagłówków.

| Prefiks ścieżki | Serwis docelowy |
|---|---|
| `/api/v1/auth`, `/api/v1/users` | identity-service |
| `/api/v1/channels` | channel-service |
| `/api/v1/offers`, `/api/v1/categories` | catalog-service |
| `/api/v1/media` | media-service |
| `/api/v1/tiktok`, `/api/v1/videos` | tiktok-integration-service |
| `/api/v1/feed` | feed-service |
| `/api/v1/search` | search-service |
| `/api/v1/questions`, `/api/v1/reviews`, `/api/v1/reports` | engagement-service |
| `/api/v1/chats`, `/ws/chat` | chat-service |
| `/api/v1/cart` | cart-service |
| `/api/v1/orders`, `/api/v1/seller/orders` | order-service |
| `/api/v1/payments`, `/api/v1/seller/payouts` | payment-service |
| `/api/v1/services`, `/api/v1/bookings`, `/api/v1/seller/bookings` | booking-service |
| `/api/v1/shipping` | shipping-service |
| `/api/v1/notifications` | notification-service |
| `/api/v1/analytics`, `/api/v1/seller/analytics` | analytics-service |

Zdrowie: `GET /actuator/health` na każdym serwisie.

## Dane i zależności

- **Magazyn danych:** brak (bezstanowy); Redis do limitów zapytań
- **Zależności:** wszystkie serwisy (routing), identity-service (klucze do walidacji JWT)
- **Kontrakty:** `rynek-contracts/openapi/` i `rynek-contracts/events/`
- **Lokalne uruchomienie:** `rynek-infra/local/`

## Kolejność budowy

Etap z §19: 1. `api-gateway`, `identity-service`, `channel-service` — konta i kanały.

## Do rozstrzygnięcia

Gateway (§2) kieruje ruch po prefiksie ścieżki, a poniższe endpointy leżą pod prefiksem innego serwisu. Możliwe rozwiązania: szczegółowe trasy w gateway przed trasami prefiksowymi albo zmiana ścieżek.

- `/api/v1/offers/{offerId}/video`: według dokumentu należy do `tiktok-integration-service`, ale prefiks w gateway kieruje do `catalog-service`.
- `/api/v1/channels/{channelId}/videos`: według dokumentu należy do `tiktok-integration-service`, ale prefiks w gateway kieruje do `channel-service`.
- `/api/v1/offers/{offerId}/questions`, `/api/v1/offers/{offerId}/like`: według dokumentu należy do `engagement-service`, ale prefiks w gateway kieruje do `catalog-service`.
- `/api/v1/channels/{channelId}/reviews`, `/api/v1/channels/{channelId}/rating-summary`: według dokumentu należy do `engagement-service`, ale prefiks w gateway kieruje do `channel-service`.
- `/api/v1/orders/{orderId}/review`: według dokumentu należy do `engagement-service`, ale prefiks w gateway kieruje do `order-service`.
- `/api/v1/bookings/{bookingId}/review`: według dokumentu należy do `engagement-service`, ale prefiks w gateway kieruje do `booking-service`.
- `/api/v1/users/me/following`: według dokumentu należy do `channel-service`, ale prefiks w gateway kieruje do `identity-service`.
