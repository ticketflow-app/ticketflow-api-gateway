# P-004

## Identificador del prompt

P-004

## Fecha

2026-10-09

## Objetivo

Diagnosticar y resolver el fallo de arranque de `user-service` en el stack local: `UnknownHostException: postgres` / "Unable to determine Dialect without JDBC metadata".

## Prompt completo utilizado

```text
"me sale este error en user-service: … Caused by: org.postgresql.util.PSQLException: The connection attempt failed. Caused by: java.net.UnknownHostException: postgres …"
```

## Contexto

- El usuario **renombró** el servicio del compose del gateway `postgres` → `postgres-user` y la BD `TicketFlow` → `user` (`POSTGRES_DB: user`, `application.properties` con default `…/user`).
- En `docker-compose.yml`, `SPRING_DATASOURCE_URL` quedó apuntando a `jdbc:postgresql://postgres:5432/user` (host antiguo `postgres`), mientras `depends_on` sí usaba `postgres-user`.

## Archivos del proyecto modificados

- `docker-compose.yml`: `SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/user` → `jdbc:postgresql://postgres-user:5432/user`.

## Diagnóstico

- `docker logs ticketflow-user-service`: `UnknownHostException: postgres` → el hostname del servicio `postgres` ya no existe en la red del compose (ahora es `postgres-user`). Sin conexión JDBC, Hibernate no puede detectar el dialecto desde metadatos → "Unable to determine Dialect without JDBC metadata".
- El contenedor `ticketflow-user-db` sí estaba sano y con la BD `user` creada (volumen `postgres_user_data`).

## Comandos ejecutados

- `docker logs ticketflow-user-service`.
- `docker inspect ticketflow-user-service` / `ticketflow-user-db`.
- `docker exec ticketflow-user-db psql -U postgres -tAc "SELECT datname FROM pg_database …"`.
- `docker compose up -d user-service`.

## Pruebas realizadas y resultado

- **Arranque:** `Started UserApplication in 6.363 seconds`; Tomcat en `:8081`.
- **Registro Eureka:** `TICKETFLOW-USER-SERVICE` con status `UP` (junto a `TICKETFLOW-INVENTORY-SERVICE` y `API-GATEWAY`).

## Observaciones (consistencia, no autorizado aún)

- `ticketflow-user-service/docker-compose.yml` sigue con servicio `postgres` y BD `TicketFlow` (consistente por sí solo, pero diverge del rename hecho en el gateway).
- Docs/`AGENTS.md` (raíz y user-service) aún mencionan `TicketFlow`; ahora la BD real es `user`.
- `user` es palabra reservada en PostgreSQL; crea la BD correctamente al ir entre comillas en el entrypoint, pero conviene considerar un nombre menos conflictivo (p. ej. `users`).

## Estado

IMPLEMENTADO