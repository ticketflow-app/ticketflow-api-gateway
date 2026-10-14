# DA-001

## ID

DA-001

## Título

Hacer que los fallbacks del circuit breaker acepten cualquier método HTTP (evitar 405 en POST)

## Contexto

- `docs_ia/contexto/arquitectura/arquitectura.md`: el gateway aplica circuit breaker + timeouts (Resilience4j) y define fallbacks cuando un servicio no está disponible.
- Estado verificado del repositorio:
  - `src/main/resources/application.yml`: la ruta `inventory-service` (`Path=/events/*/availability,/reservations,/reservations/**`) usa `CircuitBreaker` con `fallbackUri: forward:/fallback/inventory`. El `TimeLimiter` por defecto es de `3s`.
  - `src/main/java/ticketflow/api_gateway/fallback/FallbackController.java`: los endpoints de fallback están declarados con **`@GetMapping`** (solo GET): `/fallback/users` y `/fallback/inventory`.
  - `JwtAuthFilter` exige `Authorization: Bearer` para `/reservations` (no es ruta pública); el preflight `OPTIONS` y `/register`,`/login`,`/actuator` son públicos.
- **Síntoma observado (2026-10-14):** `POST http://localhost:8080/reservations` (con token) tardó **3.04 s** y devolvió **405 Method Not Allowed** con `requestId` (respuesta del gateway). El circuit breaker cortó por timeout y ejecutó `forward:/fallback/inventory`, pero el forward **conserva el método POST** y el fallback solo admite GET → 405 en lugar de 503.

## Problema

Cuando el circuit breaker/timelimiter se dispara para una petición que **no** es GET (p. ej. `POST /reservations`), el reenvío al `fallbackUri` llega a un handler declarado `@GetMapping` y Spring responde **405 Method Not Allowed**. El comportamiento correcto de un fallback es devolver **503 Service Unavailable** (o el código definido), independientemente del método original.

## Alternativas consideradas

### Alternativa A — `@RequestMapping` sin método (recomendada)

Cambiar `@GetMapping` por `@RequestMapping` en los handlers de `/fallback/users` y `/fallback/inventory`, de modo que acepten cualquier método HTTP. Mínimo y directo.

### Alternativa B — Declarar `@GetMapping` + `@PostMapping` (y otros explícitos)

Cubre los métodos usados hoy, pero es frágil (hay que recordar ampliarlo) y no cubre métodos futuros.

### Alternativa C — Configurar el fallback por ruta con un `GatewayFilter`/handler propio

Más elaborado y con más superficie; innecesario para el objetivo.

### Alternativa D — No tocar el fallback; "arreglar" solo el timeout

No resuelve la semántica: cualquier caída del servicio durante un POST seguiría devolviendo 405 en vez de 503.

## Recomendación

**Adoptar la Alternativa A.** Cambio propuesto (**aún no aplicado**):

- `src/main/java/ticketflow/api_gateway/fallback/FallbackController.java`: reemplazar `@GetMapping` por `@RequestMapping` en `usersFallback()` y `inventoryFallback()` (se conserva el cuerpo que devuelve `503` con `Map`).

## Justificación

1. Un fallback debe responder a **cualquier** método con el código de "servicio no disponible", no un 405.
2. Elimina un error engañoso que oculta la causa real (servicio no disponible / timeout).
3. Cambio mínimo, sin impacto en routing ni en el contrato de los endpoints reales.

## Ventajas

- `POST /reservations` (y cualquier otro método) recibirá **503** cuando el inventario no esté disponible, en lugar de un 405 confuso.
- Coherente con la intención de los fallbacks existentes.

## Desventajas / costos

- Cambio de código en el gateway (requiere autorización).

## Impacto

- `src/main/java/ticketflow/api_gateway/fallback/FallbackController.java` (1 archivo).
- **No** cambia: rutas, predicados, filtros, JWT, CORS ni el contrato del servicio real.

## Estado

APROBADA (2026-10-14)

> Autorizada explícitamente por el usuario ("Sí, ambos"). Trazabilidad en `docs_ia/prompts/P-005-fix-fallback-405-reservations.md`.

## Implementación (2026-10-14)

- `src/main/java/ticketflow/api_gateway/fallback/FallbackController.java`: `@GetMapping` → `@RequestMapping` en `usersFallback()` e `inventoryFallback()` (import `GetMapping` → `RequestMapping`).

Verificado (2026-10-14):

- `POST http://localhost:8080/fallback/inventory` (con JWT) → **HTTP 503** (antes `405`) con cuerpo `{"service":"inventory-service","status":503,...}`.
- `POST http://localhost:8080/reservations` (con JWT) → **HTTP 200** (ya no se dispara el fallback porque Kafka quedó corregido en `inventory-service`, `DA-007`).
