# P-005

## Identificador del prompt

P-005

## Fecha

2026-10-14

## Objetivo

Diagnosticar el **405 Method Not Allowed** que devuelve el gateway al crear una reserva (`POST /reservations`) y proponer la corrección del fallback (`DA-001`).

## Prompt completo utilizado

```text
"me sale todo esto en el microservicio de inventorty, estoy intentando acceder al metodo de crear una reservations con la url http://localhost:8080/reservations pero me sale error 405 method not allowed:"
```

## Archivos de contexto consultados

- `AGENTS.md` (raíz y del gateway) — gobernanza §1, §2, §3, §8, §10.
- `docs_ia/contexto/arquitectura/arquitectura.md` (resiliencia, circuit breaker y fallbacks).

## Archivos del proyecto analizados

- `src/main/resources/application.yml` (ruta `inventory-service`, `CircuitBreaker` + `fallbackUri`, `TimeLimiter 3s`).
- `src/main/java/ticketflow/api_gateway/fallback/FallbackController.java`.
- `src/main/java/ticketflow/api_gateway/filter/JwtAuthFilter.java`.
- `docker-compose.yml` (Kafka `kafka:9092`, envs de `inventory-service`).

## Archivos de otros repositorios analizados

- `ticketflow-inventory-service/src/main/java/com/inventory/service/infrastructure/config/KafkaConfig.java`.
- `ticketflow-inventory-service/src/main/java/com/inventory/service/infrastructure/adapter/out/messaging/KafkaReservationEventPublisher.java`.

## Resumen de la respuesta generada

- `POST /reservations` con token tardó **3.04 s** y devolvió **405** con `requestId`: el `TimeLimiter` (3s) disparó el `CircuitBreaker` y ejecutó `forward:/fallback/inventory`, pero el fallback está declarado **solo GET** (`@GetMapping`) y el forward **preserva el método POST** → 405.
- El corte a 3s ocurre porque el `inventory-service` bloquea en `KafkaReservationEventPublisher.publish(...).join()`: su `KafkaConfig` fija `bootstrap.servers` a `localhost:9092` (hardcodeado) e ignora `SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:9092`.
- `GET /reservations` devuelve 405 inmediato porque el endpoint es POST-only.

## Propuestas realizadas por la IA

- `DA-001` (gateway, `PROPUESTA`): declarar los fallbacks con `@RequestMapping` (cualquier método) para que devuelvan **503** en vez de 405.
- Nota cruzada: `inventory-service` requiere `DA-007` (bootstrap de Kafka desde configuración).

## Decisiones pendientes

- Aprobar `DA-001` y autorizar el cambio en `FallbackController.java`.
- Aprobar `DA-007` del `inventory-service`.

## Autorización humana

El usuario autorizó explícitamente: **"Sí, ambos"** (aplicar `DA-007` del inventario y `DA-001` del gateway).

## Cambios aplicados

- `src/main/java/ticketflow/api_gateway/fallback/FallbackController.java`: `@GetMapping` → `@RequestMapping` en `/fallback/users` y `/fallback/inventory`.
- (Cross-repo) `ticketflow-inventory-service/.../KafkaConfig.java`: bootstrap desde `spring.kafka.bootstrap-servers` (`DA-007`).

## Comandos ejecutados

- `docker compose up -d --build inventory-service api-gateway`.
- `curl` a `POST http://localhost:8080/reservations` y `POST http://localhost:8080/fallback/inventory` (con JWT).

## Pruebas realizadas y resultado

- **`POST /fallback/inventory` (con token):** `HTTP 503` (antes `405`).
- **`POST /reservations` (con token):** `HTTP 200` en 0.075 s (antes `405` en 3.04 s).

## Pendientes

- Ninguno para esta corrección.

## Estado

IMPLEMENTADO
