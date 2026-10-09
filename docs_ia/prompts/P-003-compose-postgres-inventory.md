# P-003

## Identificador del prompt

P-003

## Fecha

2026-10-09

## Objetivo

Registrar el cambio autorizado en `api-gateway` (a pedido del usuario): reemplazar el script de init de la BD `inventory` por un servicio `postgres` dedicado a inventario y desacoplar el aprovisionamiento del gateway.

## Prompt completo utilizado

```text
"pero por que hay un init en el api gateway? quiero que sea como en user-service, es decir, usar flyway y que se defina ahi mismo en el contenedor de inventory service, ademas, tal cual como esta en user-service"
```

Autorización posterior (opción elegida): "Postgres dedicado a inventario (Recomendado)".

## Archivos de contexto consultados

- `AGENTS.md` (sección Gobernanza `docs_ia`).
- `docker-compose.yml`.

## Archivos del proyecto modificados

- `docker-compose.yml`.
- Eliminado: `postgres-init/01-create-inventory-db.sql` y el directorio `postgres-init/`.

## Resumen de la respuesta generada

- Se agregó el servicio `postgres-inventory` (`postgres:18-alpine`, `POSTGRES_DB: inventory`, volumen `postgres_inventory_data`, healthcheck).
- `inventory-service` ahora depende de `postgres-inventory` y usa `SPRING_R2DBC_URL` / `SPRING_FLYWAY_URL` apuntando a `postgres-inventory:5432/inventory`.
- Se eliminó el montaje `./postgres-init:/docker-entrypoint-initdb.d:ro` y el script de init.

## Comandos ejecutados

- `docker compose -f docker-compose.yml config --quiet`.

## Pruebas realizadas y resultado

- **docker compose config:** válido (exit 0).

## Decisiones pendientes

- Ninguna.

## Estado

IMPLEMENTADO
