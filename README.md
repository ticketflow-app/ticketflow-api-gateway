# ticketflow-api-gateway

API Gateway de TicketFlow basado en **Spring Cloud Gateway** (Spring Boot 4 + Java 21). Punto único de entrada hacia los microservicios; actúa también como balanceador de carga con **descubrimiento de servicios vía Eureka (`lb://`)**, aplica **rate limiting con Redis**, **circuit breakers** (Resilience4j) y **valida JWT** antes de reenviar cada petición.

## Stack

- **Java 21** + **Spring Boot 4.1.1** + **Spring Cloud 2025.1.3**
- Spring Cloud Gateway (server-webflux, reactivo)
- Eureka Client (`lb://` en las rutas) + Spring Cloud LoadBalancer
- Circuit Breaker con Resilience4j (reactor)
- Rate Limiter con Redis (`spring-boot-starter-data-redis-reactive`)
- Filtro global de autenticación JWT (jjwt 0.12.6)
- Actuator (health, metrics y endpoint `gateway` para inspeccionar rutas)

## Topología local

| Servicio | Puerto |
|---|---|
| API Gateway | 8080 |
| user-service | 8081 |
| eureka-server | 8761 |
| frontend | 8081 (CORS) |

## Cómo correr

Requiere **Redis** corriendo en `localhost:6379` para el rate limiter (si no, comenta los filtros `RequestRateLimiter` de las rutas).

```bash
./mvnw spring-boot:run
```

- API: http://localhost:8080
- Rutas registradas: http://localhost:8080/actuator/gateway/routes
- Health: http://localhost:8080/actuator/health

## Rutas

| Ruta del gateway | Microservicio destino | Endpoints |
|---|---|---|
| `/register`, `/login`, `/me`, `/users/**` | `user-service` (`localhost:8081`) | registrarse, login, datos del token, cambio de rol |
| `/api/tickets/**` | `ticket-service` (placeholder, aún no implementado) | — |

Cada ruta tiene Circuit Breaker con fallback (`/fallback/users`, `/fallback/tickets`) que responde `503` degradado cuando el servicio destino no responde, y rate limiter por IP (10 req/s, ráfaga de 20). Un filtro global valida el JWT (`Authorization: Bearer <token>`) en todas las rutas salvo `/register`, `/login`, `/actuator/**` y los preflight `OPTIONS`.

## Configuración por variables de entorno

- `USER_SERVICE_URI` / `TICKET_SERVICE_URI`: fuerza una URL directa (`http://...`) para dev sin Eureka; si no se define, se usa `lb://<service>` resuelto vía Eureka.
- `EUREKA_URI`: defaultZone de Eureka (default `http://localhost:8761/eureka`).
- `EUREKA_ENABLED`: `false` desactiva el cliente Eureka (dev local sin eureka-server).
- `REDIS_HOST` / `REDIS_PORT`: conexión a Redis.
- `JWT_SECRET`: secreto compartido con user-service para validar el JWT (mínimo 32 bytes para HS256).
- `SERVER_PORT`: puerto del gateway (default 8080).

## Documentación

- [Documentación de arquitectura del proyecto](docs/documentacion_arquitectura.md)
- [Registro de conversaciones con IA](docs/ia/registro_conversaciones.md)