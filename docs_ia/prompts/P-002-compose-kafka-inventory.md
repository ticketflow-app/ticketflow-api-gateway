# P-002

## Identificador del prompt

P-002

## Fecha

2026-10-09

## Objetivo

Registrar el cambio autorizado en `api-gateway`: incorporar `inventory-service` al `docker-compose.yml` del stack local, junto con sus dependencias (Kafka y la base de datos `inventory`).

## Prompt completo utilizado

```text
Durante la tarea de crear el Dockerfile de inventory-service, el usuario solicitó:
"Ahora quiero que crees el dockerfile y lo agregues al docker-compose"
```

## Archivos de contexto consultados

- `AGENTS.md` (sección Gobernanza `docs_ia`).
- `docker-compose.yml` existente (servicios `eureka-server`, `postgres`, `user-service`, `redis`, `api-gateway`).

## Archivos del proyecto modificados

- `docker-compose.yml`.
- `postgres-init/01-create-inventory-db.sql` (nuevo).

## Resumen de la respuesta generada

- Se agregó el servicio `inventory-service` (build `../ticketflow-inventory-service`, `dockerfile`, puerto `8082`) con variables de entorno para R2DBC (`inventory`), Redis, Kafka y Eureka, y `depends_on` a eureka-server, postgres (healthcheck), redis y kafka.
- Se agregó el servicio `kafka` (`apache/kafka:3.9.1`, KRaft single-node, listener interno `kafka:9092`), que el compose no tenía.
- Se montó `./postgres-init:/docker-entrypoint-initdb.d:ro` en `postgres` y se creó el script que crea la BD `inventory`.

## Comandos ejecutados

- `docker compose -f docker-compose.yml config --quiet`.

## Pruebas realizadas y resultado

- **`docker compose config`:** válido (sin errores).

## Decisiones pendientes

- Ninguna.

## Estado

IMPLEMENTADO
