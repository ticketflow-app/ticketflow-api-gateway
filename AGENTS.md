# AGENTS.md — ticketflow-api-gateway

Edge de TicketFlow: enrutamiento, validación JWT, rate limit y circuit breaker. Este `docker-compose.yml` levanta el **stack completo local**.

## Servicio

| Campo | Valor |
|---|---|
| Rol | Edge: routing, JWT validation, rate limit, circuit breaker |
| Eureka name | `api-gateway` |
| Puerto | 8080 |
| Depende de | Redis, Eureka, user-service, inventory-service |
| Stack | Java 21, Spring Boot 4.1.1, Spring Cloud 2025.1.3 |

## Comandos

```powershell
.\mvnw.cmd test                 # los tests ponen eureka.client.enabled=false
.\mvnw.cmd spring-boot:run
docker compose up -d --build    # stack completo (eureka + postgres + redis + kafka + user-service + inventory-service + gateway)
```

## Gotchas

- **Paquete base no estándar**: `ticketflow.api_gateway` (con guion bajo) — main class más `config/`, `filter/`, `fallback/`.
- Gateway reactivo: `spring-cloud-starter-gateway-server-webflux`, con config bajo `spring.cloud.gateway.server.webflux`.
- **Spring Boot 4**: usa `spring-boot-starter-webmvc` (no `-web`) y starters de test tipo `*-webmvc-test`.
- `JwtAuthFilter` considera públicas `/register`, `/login`, `/actuator*` y el preflight `OPTIONS`; el resto exige `Authorization: Bearer`.
- JWT: el gateway **valida** (user-service emite). `JWT_SECRET` compartido y ≥32 bytes. jjwt `0.12.6` aquí (user-service usa `0.13.0`).
- CORS permite solo `http://localhost:8081`.
- Fallbacks del circuit breaker: `/fallback/users`, `/fallback/tickets`, `/fallback/inventory`.
- Ruta hacia inventario: `lb://ticketflow-inventory-service` (`/events/*/availability`, `/reservations`), con circuit breaker `inventoryCircuitBreaker`.
- El compose incluye `kafka` (`apache/kafka:3.9.1`, KRaft single-node) y un `postgres` dedicado a inventario (`postgres-inventory`, `POSTGRES_DB: inventory`, volumen `postgres_inventory_data`). Inventario crea su esquema con **Flyway** al arrancar; **no** hay scripts de init en este repo.
- El Dockerfile es minúscula (`dockerfile`).

## Convenciones

- Hexagonal + Clean + DDD en los componentes de código.
- Código, identificadores y comentarios **en inglés**; documentación en español.

## Gobernanza (docs_ia)

- `docs_ia/contexto/` es la **única fuente autorizada** de requisitos, arquitectura, calidad y pruebas.
- Decisiones arquitectónicas: proponer en `docs_ia/decisiones/DA-xxx-*.md` (estado `PROPUESTA`) y esperar aprobación humana.
- Trazabilidad: registrar interacciones relevantes en `docs_ia/prompts/P-xxx-*.md`.
- No agregar/quitar/actualizar dependencias ni modificar archivos (salvo trazabilidad/propuestas) sin autorización humana explícita.

## Grafo (codebase-memory)

- Proyecto: `C-Users-Juan-Diego-Duque-Documents-Programacion-ticketflow-ticketflow-api-gateway`.
- Este repo es git → `detect_changes --base-branch main` funciona.
- Arquitectura entre servicios → grafo padre `C-Users-Juan-Diego-Duque-Documents-Programacion-ticketflow`.
- **Cambiar de rama NO refresca este grafo automáticamente**: tras `git checkout`, fuerza re-index con `index_repository --repo-path . --mode full`.

## No tocar

- `.mvn/`, `.idea/`, `target/` = build/IDE output.
