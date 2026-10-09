# P-001

## Identificador del prompt

P-001

## Fecha

2026-10-09

## Objetivo

Registrar el cambio autorizado en `api-gateway`: nueva ruta hacia `inventory-service`, su circuit breaker y su fallback, como parte del alcance adicional autorizado durante la integración de `inventory-service` con Eureka (DA-003).

## Prompt completo utilizado

```text
Durante la tarea de integrar inventory-service con Eureka, el usuario autorizó como alcance adicional:
"Ruta en api-gateway (lb://…)".
```

## Archivos de contexto consultados

- `AGENTS.md` (sección Gobernanza `docs_ia`).

## Archivos del proyecto modificados

- `src/main/resources/application.yml`.
- `src/main/java/ticketflow/api_gateway/fallback/FallbackController.java`.

## Resumen de la respuesta generada

Se añadió la ruta `inventory-service` con `uri: ${INVENTORY_SERVICE_URI:lb://ticketflow-inventory-service}`, predicados `Path=/events/*/availability,/reservations,/reservations/**`, filtro `CircuitBreaker` (`inventoryCircuitBreaker`, fallback `forward:/fallback/inventory`) y `RequestRateLimiter`. Se registraron las instancias `inventoryCircuitBreaker` en `resilience4j` (circuitbreaker y timelimiter). Se añadió el endpoint `/fallback/inventory` en `FallbackController`.

## Comandos ejecutados

- `.\mvnw.cmd -B -DskipTests compile`.
- `.\mvnw.cmd -B test`.

## Pruebas realizadas y resultado

- **compile:** BUILD SUCCESS.
- **test:** BUILD SUCCESS (1 test, 0 fallos). El `ApiGatewayApplicationTests` levanta el contexto con `eureka.client.enabled=false`.

## Decisiones pendientes

- Ninguna.

## Estado

IMPLEMENTADO
