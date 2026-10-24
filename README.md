# rynek-api-gateway

`api-gateway` (Spring Cloud Gateway)

> Status: routing z §2, walidacja JWT (domyślnie wyłączona), CORS i limity zapytań są gotowe. Agregacja nagłówków jeszcze nie.

## Uruchomienie

```bash
./mvnw spring-boot:run   # port 8080, zmienna PORT
```

Adresy serwisów docelowych: zmienne `<SERWIS>_SERVICE_URL` (np. `CATALOG_SERVICE_URL`), domyślnie `http://localhost:<port serwisu>`. Ścieżki `/internal/**` nie są wystawiane.

| Zmienna | Domyślnie | Znaczenie |
|---|---|---|
| `JWT_ENFORCE` | `false` | `true`: ścieżki niepubliczne wymagają JWT. Wymaga działającego identity-service (JWKS) |
| `JWT_ISSUER` | `rynek-identity` | Oczekiwany `iss` w tokenie |
| `IDENTITY_SERVICE_URL` | `http://localhost:8081` | Także źródło kluczy: `GET {url}/internal/auth/jwks` |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:4200` | Lista originów rozdzielona przecinkami |
| `REDIS_HOST`, `REDIS_PORT` | `localhost`, `6379` | Redis do limitów (`rynek-infra/local`) |
| `REDIS_TIMEOUT` | `500ms` | Timeout połączenia i komendy Redis. Po nim limiter przepuszcza żądanie |
| `RATE_LIMIT_REPLENISH_RATE`, `RATE_LIMIT_BURST_CAPACITY` | `20`, `40` | Żądań na sekundę i maksymalny zryw na klienta |

Gdy serwis docelowy nie przyjmuje połączeń, gateway odpowiada `503 Service Unavailable` w formacie RFC 7807 (`DownstreamUnavailableHandler`) zamiast domyślnego 500. Frontend w trybie `auto` przechodzi wtedy na dane przykładowe.

Błędy zgłaszane przez sam gateway, np. `404` dla ścieżki bez trasy (w tym `/internal/**`), też mają format RFC 7807 (`ResponseStatusProblemHandler`). Odpowiedzi serwisów docelowych przechodzą bez zmian.

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

## Uwierzytelnianie

Gateway sprawdza tylko, czy użytkownik jest zalogowany. Role (`seller`, `admin`) i własność zasobu sprawdza serwis, który też waliduje JWT. Nagłówek `Authorization` trafia do serwisu bez zmian.

- Token: JWT RS256 wystawiany przez identity-service, z claimami `iss=rynek-identity`, `sub` (id użytkownika), `roles` i `exp`. Klucze publiczne gateway pobiera z JWKS identity.
- Publiczne endpointy (`pub` w README serwisów) są w `security/PublicEndpoints.java`. Dodając publiczny endpoint w serwisie, dopisz go także tam.
- Brak tokenu albo zły token daje `401 problem+json` z nagłówkiem `WWW-Authenticate: Bearer`. Frontend wtedy odświeża token.
- Na endpointach publicznych gateway nie sprawdza nagłówka `Authorization`: wygasły token nie daje 401 na stronie głównej ani na `POST /auth/refresh`. Nagłówek trafia do serwisu bez zmian.
- `/ws/chat` jest przepuszczany bez tokenu, bo przeglądarka nie wysyła nagłówka przy WebSocket. Token przychodzi w ramce STOMP `CONNECT` i sprawdza go chat-service.
- `JWT_ENFORCE=false` (domyślnie) przepuszcza wszystko, a przy starcie w logu pojawia się ostrzeżenie. Dzięki temu frontend w trybie `auto` dalej dostaje 501 i pokazuje dane przykładowe.
- identity-service wystawia już prawdziwe tokeny (RS256, JWKS w `/internal/auth/jwks`). `JWT_ENFORCE=true` włączaj, gdy identity działa: bez JWKS gateway odrzuca każdy chroniony endpoint (`401`), a frontend wylogowuje użytkownika. Dlatego domyślnie zostaje `false`, żeby dało się pracować na samych mockach.

## CORS i limity zapytań

- CORS jest skonfigurowany w Spring Security, więc preflight `OPTIONS` przechodzi także przy włączonym JWT. Lokalnie frontend i tak używa proxy (`proxy.conf.json`).
- Limity działają przez `RequestRateLimiter` z Redisem na wszystkich trasach. Klucz to `user:<sub>` dla zalogowanych, a dla pozostałych `ip:<adres>`. Po przekroczeniu gateway zwraca `429`.
- Gdy Redis nie działa, żądania przechodzą bez limitu, a w logu przy każdym żądaniu pojawia się `ERROR ... Error calling rate limiter lua`. Redis nie wpływa na `/actuator/health`.
- Dotyczy to też Redisa, który padł w trakcie pracy albo przestał odpowiadać. Po zerwaniu połączenia komendy są od razu odrzucane (`REJECT_COMMANDS`), a zawieszony Redis blokuje żądanie najwyżej na `REDIS_TIMEOUT`. Po powrocie Redisa limity działają znowu bez restartu gatewaya.

## Dane i zależności

- **Magazyn danych:** brak (bezstanowy); Redis do limitów zapytań
- **Zależności:** wszystkie serwisy (routing), identity-service (klucze do walidacji JWT)
- **Kontrakty:** `rynek-contracts/openapi/` i `rynek-contracts/events/`
- **Lokalne uruchomienie:** `rynek-infra/local/`

## Kolejność budowy

Etap z §19: 1. `api-gateway`, `identity-service`, `channel-service` — konta i kanały.

## Wyjątki routingu

Gateway (§2) kieruje ruch po prefiksie ścieżki, a poniższe endpointy leżą pod prefiksem innego serwisu. Gateway obsługuje je trasami szczegółowymi (`order: -1`), które mają pierwszeństwo przed trasami po prefiksie. Ścieżki pozostają bez zmian (decyzja z 2026-10-24). Test `RoutingTests` w `rynek-api-gateway` sprawdza, czy każda z nich trafia do właściwego serwisu.

- `/api/v1/offers/{offerId}/video`: należy do `tiktok-integration-service` (prefiks wskazuje `catalog-service`).
- `/api/v1/channels/{channelId}/videos`: należy do `tiktok-integration-service` (prefiks wskazuje `channel-service`).
- `/api/v1/offers/{offerId}/questions`, `/api/v1/offers/{offerId}/like`: należy do `engagement-service` (prefiks wskazuje `catalog-service`).
- `/api/v1/channels/{channelId}/reviews`, `/api/v1/channels/{channelId}/rating-summary`: należy do `engagement-service` (prefiks wskazuje `channel-service`).
- `/api/v1/orders/{orderId}/review`: należy do `engagement-service` (prefiks wskazuje `order-service`).
- `/api/v1/bookings/{bookingId}/review`: należy do `engagement-service` (prefiks wskazuje `booking-service`).
- `/api/v1/users/me/following`: należy do `channel-service` (prefiks wskazuje `identity-service`).
