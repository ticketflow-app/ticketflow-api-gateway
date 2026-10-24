# AGENTS.md — ticketflow-api-gateway

Edge de TicketFlow: routing, validación de JWT, rate limit y circuit breaker hacia los demás servicios.

> Este archivo orienta; **la fuente de verdad del proyecto es `docs_ia/contexto`**. El diseño, los requisitos y la arquitectura no se describen aquí.

## Estado

| Campo | Valor |
|---|---|
| Rol | API Gateway (edge) |
| Eureka name | `api-gateway` |
| Puerto | 8080 — coincide con la especificación |
| Depende de | Redis, Eureka, user-service, inventory-service |
| Stack (estándar del proyecto) | **Java 25 LTS**, **Spring Boot 4.1.1**, **Spring Cloud 2025.1.3**, Spring Cloud Gateway **5.0.x** (WebFlux), **jjwt 0.13.0**, **Redis 8.x** — hoy en Java 21, jjwt 0.12.6 y Redis 7 ⚠️ |
| Prefijo de rutas | **`/api/` obligatorio** (norma del proyecto); hoy las rutas están en la raíz ⚠️ |
| Contrato HTTP | `docs_ia/contexto/contrato_api.md` |

## Contrato y endpoints

Detalle completo (tabla de rutas, filtros, límites y desviaciones): `docs_ia/contexto/contrato_api.md`.
Descripción de la arquitectura: `docs_ia/contexto/arquitectura/arquitectura.md`.

| Ruta | Destino | Auth | Filtros |
|---|---|---|---|
| `/register`, `/login`, `/me`, `/users/**` | `lb://ticketflow-user-service` | pública solo `/register` y `/login` | CircuitBreaker → `forward:/fallback/users` + RequestRateLimiter (10/s, ráfaga 20, por IP) |
| `/events/*/availability`, `/reservations`, `/reservations/**` | `lb://ticketflow-inventory-service` | exige Bearer | CircuitBreaker → `forward:/fallback/inventory` + RequestRateLimiter (10/s, ráfaga 20, por IP) |
| `/actuator/health`, `/actuator/info`, `/actuator/metrics`, `/actuator/gateway` | local | público | — |
| `/fallback/users`, `/fallback/inventory` | local | público | respuesta 503 cuando el circuit breaker está abierto |

Desviaciones más relevantes frente a la especificación de plataforma (`docs/Microservicios.docx`,
sección 2):

- **Sin prefijo `/api`**: la norma del proyecto exige que toda ruta de negocio vaya bajo `/api/**`. La tabla de rutas objetivo y su correspondencia con las actuales están en `docs_ia/contexto/contrato_api.md` §5.
- **Solo 2 destinos** de los 8 de la spec (faltan catálogo, check-in, notificaciones, reseñas, analítica).
- **Rate limit único de 10/s** en lugar de límites por ruta (5/s en login, 20/s en reservas, 50/s en catálogo) y con clave **por IP**, no por `sub` del token.
- **Sin cabeceras de correlación ni identidad** (`X-Request-Id`, `traceparent`, `X-User-Id`, `X-User-Roles`).
- **Sin revocación de tokens**: solo se validan firma y expiración (no se consulta `auth:revoked:*`).
- **Java 21** (estándar: **25 LTS**) · **jjwt 0.12.6** (estándar: **0.13.0**) · **Redis 7** (estándar: **8.x**).
- **Sin `/actuator/prometheus`**: solo `/actuator/metrics`.

## Docs

- `docs_ia/contexto/contexto_negocio.md` — contexto de negocio.
- `docs_ia/contexto/contrato_api.md` — **contrato HTTP vigente**: tabla de enrutamiento, filtros, límites de tasa, auth y desviaciones frente a la especificación de plataforma.
- `docs_ia/contexto/arquitectura/` — `arquitectura.md` (pipeline, rutas, resiliencia, CORS, observabilidad), `desiciones_arquitectonicas.md`.
- `docs_ia/contexto/requisitos/` — `casos_uso.md`, `epicas_feactures.md`, `historias_usuarios.md`.
- `docs_ia/contexto/calidad/` — `atributos_calidad.md`, `escenarios_calidad.md`.
- `docs_ia/contexto/pruebas/` — `estrategia_pruebas.md`, `pruebas_cargas.md`.
- `docs_ia/decisiones/` — decisiones arquitectónicas (`DA-xxx`); `DA-001-fallback-multi-metodo.md`.
- `docs_ia/prompts/` — trazabilidad de uso de IA (`P-xxx`).

## Comandos

```powershell
.\mvnw.cmd test              # los tests desactivan Eureka (src/test/resources/application.properties)
.\mvnw.cmd spring-boot:run   # gateway en :8080
docker compose up -d --build # stack completo local (eureka + postgres + redis + kafka + 3 servicios)
```

## Gobernanza (para OpenCode)

> Adaptación para **OpenCode** de las reglas de `.clinerules` (ver el archivo completo en los repos que lo tienen: `ticketflow-inventory-service`, `ticketflow-frontend`). Aplica a todo el trabajo en este repo; su fuente de verdad es `docs_ia/contexto`.

### 1. Fuente de contexto

- `docs_ia/contexto` es la **única fuente autorizada** para: requisitos, atributos de calidad, arquitectura, diseño, estrategia de pruebas y restricciones del proyecto.
- No asumir información que no esté definida en `docs_ia/contexto`.
- Antes de una tarea relevante, revisar los archivos de `docs_ia/contexto` necesarios para esa tarea.
- Si falta información, distinguir entre el hecho conocido, la inferencia y la propuesta; si la tarea entra en conflicto con el contexto, detener el cambio afectado y pedir aclaración.

### 2. Decisiones arquitectónicas

- No tomar decisiones arquitectónicas importantes de forma autónoma.
- Cuando haga falta decidir, analizar alternativas y proponer una recomendación.
- Crear la propuesta en `docs_ia/decisiones/` en Markdown, usando el siguiente identificador `DA-xxx` disponible.
- Esperar aprobación humana antes de implementar la decisión propuesta.
- Una decisión aprobada queda documentada en `docs_ia/decisiones/`.
- No sobrescribir decisiones anteriores; si una decisión cambia, crear una nueva que explique el cambio.
- Cada decisión debe incluir: contexto, problema, alternativas consideradas, recomendación, ventajas, desventajas, impacto y estado.
- Estado `PROPUESTA` hasta recibir aprobación humana explícita; solo entonces `APROBADA` y aplicable.

### 3. Código

- Puede leer y modificar el código cuando la tarea haya sido aprobada.
- No hacer cambios importantes basados en suposiciones.
- Antes de cambios arquitectónicos, explicar brevemente qué se modificará y por qué.
- Mantener coherencia con las decisiones aprobadas.
- Nomenclatura **en inglés**: variables, métodos, clases, interfaces, paquetes, endpoints, DTOs, entidades, tablas, columnas y archivos de código.
- Comentarios y documentación técnica dentro del código, **en inglés**.
- No usar nombres en español en el código, salvo nombres externos obligados por una API, librería o requisito existente.
- Nomenclatura consistente y descriptiva según el lenguaje y framework.

### 4. Documentación

- Toda decisión arquitectónica o técnica importante se documenta en Markdown.
- Identificadores consecutivos: `DA-001`, `DA-002`, `DA-003`, ...
- No sobrescribir decisiones anteriores; si una decisión cambia, crear una nueva que explique el cambio.
- Cada decisión debe incluir: contexto, problema, alternativas consideradas, recomendación, ventajas, desventajas, impacto y estado.
- Estado `PROPUESTA` hasta recibir aprobación humana explícita; solo entonces `APROBADA` y aplicable.
- Documentación de proyecto en **español**; código y comentarios técnicos en **inglés**.

### 5. Pruebas

- No modificar la estrategia de pruebas definida en `docs_ia/contexto` sin proponerlo antes.
- Cuando corresponda, crear o modificar pruebas junto con el código.
- No introducir nuevas herramientas o frameworks de pruebas sin analizar antes su necesidad y compatibilidad.
- Las pruebas deben verificar comportamientos concretos, no limitarse a confirmar que las clases existen.

### 6. Gestión de dependencias

- Antes de agregar, eliminar, actualizar o reemplazar una dependencia, revisar los archivos relevantes de `docs_ia/contexto`.
- Revisar siempre el manifiesto de dependencias actual (`pom.xml`) antes de proponer cambios.
- Clasificar las dependencias como: indispensables, recomendadas, opcionales o no justificadas actualmente.
- Para cada dependencia propuesta indicar: propósito, funcionalidad, relación con la arquitectura, requisito que satisface, alternativas, impacto técnico y compatibilidad con las versiones actuales.
- Verificar compatibilidad con: versión de Java, versión de Spring Boot, versión de Maven y arquitectura definida.
- No agregar dependencias por conveniencia, costumbre o sugerencia automática de una herramienta.
- Principio de mínima dependencia: solo las justificadas por requisitos y arquitectura aprobada.
- **No agregar, eliminar, actualizar ni modificar dependencias sin autorización humana explícita.**
- Antes de modificar el manifiesto, presentar análisis y propuesta; tras la autorización, aplicar solo lo aprobado.
- Toda modificación importante de dependencias se documenta en `docs_ia/decisiones`.

### 7. Uso de herramientas

- Puede leer archivos; crear y modificar cuando la tarea esté aprobada; ejecutar comandos necesarios para la tarea aprobada.
- No ejecutar comandos que modifiquen el proyecto sin autorización cuando los cambios no estén aprobados.
- Pedir aprobación antes de comandos destructivos o peligrosos y antes de instalar paquetes, dependencias, herramientas o software.
- No eliminar archivos, dependencias, configuraciones o código sin autorización explícita.
- Si una herramienta propone cambios automáticos, analizar primero y pedir autorización antes de aplicarlos.

### 8. Autorización de cambios

- Toda tarea que modifique código, configuración, arquitectura, dependencias o infraestructura requiere autorización humana si no estaba previamente aprobada.
- Analizar primero, modificar después.
- Una autorización específica no autoriza cambios adicionales no relacionados.
- No interpretar expresiones ambiguas como autorización para cambios importantes.
- Si la tarea requiere una decisión no definida en `docs_ia/contexto`, detener la implementación y presentar la decisión.
- El permiso de lectura no implica permiso de escritura: cada cambio debe caer dentro del alcance autorizado.

### 9. Comunicación

- Explicar las decisiones de forma breve y técnica.
- Si falta información en `docs_ia/contexto`, indicarlo explícitamente.
- Diferenciar siempre entre: información definida, suposición, propuesta y decisión aprobada.
- Presentar las alternativas técnicamente válidas antes de elegir una.
- No presentar una propuesta como si fuera una decisión aprobada.
- Informar qué archivos se modificarán antes de cambios importantes.
- Tras cambios autorizados, informar: archivos modificados, cambios realizados, comandos ejecutados, pruebas realizadas, resultado y pendientes.

### 10. Trazabilidad del uso de IA

- Toda interacción relevante debe quedar registrada, incluso si es solo análisis sin cambios de código.
- Se registra desde que se ejecuta el prompt y no requiere autorización.
- Ubicación: `docs_ia/prompts`, un archivo Markdown independiente por interacción, con identificadores consecutivos `P-001`, `P-002`, ...
- Contenido mínimo: identificador, fecha, objetivo, prompt completo, archivos de contexto consultados, archivos analizados, resumen de la respuesta, propuestas, decisiones pendientes y estado.
- Estados permitidos: `ANALISIS` | `PROPUESTA` | `APROBADO` | `RECHAZADO` | `IMPLEMENTADO`.
- Si hay propuesta arquitectónica o técnica, crear también el documento correspondiente en `docs_ia/decisiones`.
- El registro de trazabilidad y la propuesta documental **no** autorizan a modificar código, configuración, dependencias o infraestructura.
- Ningún archivo (salvo trazabilidad y propuestas) se modifica sin autorización humana explícita.
- Si solo se pide análisis, ejecutarlo y documentarlo sin tocar código.
- Si luego se autoriza, actualizar el registro con autorización, cambios, archivos, comandos, pruebas, resultado y estado final.
- Nunca sobrescribir registros históricos; si algo cambia, crear un nuevo registro relacionado.
- La trazabilidad debe permitir reconstruir: Contexto → Prompt → Respuesta IA → Propuesta → Autorización humana → Implementación → Pruebas → Resultado.

## Grafo (codebase-memory)

- Proyecto de grafo: `C-Users-Juan-Diego-Duque-Documents-Programacion-ticketflow-ticketflow-api-gateway`.
- Este repo es git → `detect_changes --base-branch main` funciona contra este proyecto.
- Arquitectura entre servicios → grafo padre `C-Users-Juan-Diego-Duque-Documents-Programacion-ticketflow` (foto del estado actual de los 5 checkouts).
- **Cambiar de rama NO refresca este grafo automáticamente** (el watcher sigue la raíz, que no es git). Tras `git checkout` o al editar, fuerza re-index:
  ```powershell
  powershell -File ..\sync-graph.ps1 -Service ticketflow-api-gateway   # desde la raíz
  ```
  o, desde esta carpeta: `index_repository --repo-path . --mode full`.

## Gotchas

- **Base package**: `ticketflow.api_gateway` (con underscore, fuera de lo estándar). Solo hay 4 clases: `ApiGatewayApplication`, `config/RateLimitConfig`, `fallback/FallbackController`, `filter/JwtAuthFilter`.
- **`JwtAuthFilter`** deja públicos `/register`, `/login`, `/actuator` (por prefijo) y cualquier `OPTIONS` (preflight). Todo lo demás exige `Authorization: Bearer`.
- **Rutas** (`application.yml`): `user-service` → `/register,/login,/me,/users/**` contra `lb://ticketflow-user-service`; `inventory-service` → `/events/*/availability,/reservations,/reservations/**` contra `lb://ticketflow-inventory-service`.
- Cada ruta lleva **CircuitBreaker + RequestRateLimiter** (Redis: 10 req/s, ráfaga 20). Sin Redis, esas rutas fallan.
- **Fallbacks**: `forward:/fallback/users` y `forward:/fallback/inventory` → `fallback/FallbackController` (DA-001: un solo handler multi-método).
- `lb://` se resuelve vía Eureka; para dev sin Eureka usa `USER_SERVICE_URI` / `INVENTORY_SERVICE_URI` con URL directa, o `EUREKA_ENABLED=false`.
- **JWT**: `jwt.secret` debe coincidir con el de user-service y tener ≥32 bytes (HS256). jjwt `0.12.6` aquí (estándar del proyecto: `0.13.0`) vs `0.13.0` en user-service.
- **CORS**: solo `http://localhost:8081`. El frontend real (`:3000`) entra por su BFF server-side, no por el navegador.
- **Configuración de Gateway en Spring Boot 4**: vive bajo `spring.cloud.gateway.server.webflux` y el starter es `spring-cloud-starter-gateway-server-webflux`.
- Resilience4j: ventana 20, umbral 50 %, 10 s en `OPEN` con paso automático a `HALF_OPEN`; timeout 3 s.
- **El `docker-compose.yml` de esta carpeta es el stack completo local**: eureka, `postgres-user` (BD `user`), `postgres-inventory` (BD `inventory`), redis, kafka, user-service, inventory-service y api-gateway.
- **El hostname de la BD debe coincidir con el nombre del servicio** (`postgres-user`): si no, `UnknownHostException` y Hibernate aborta con *"Unable to determine Dialect without JDBC metadata"*.
- Dockerfile en minúscula (`dockerfile`), así lo referencia el compose.
- **Las rutas no llevan prefijo `/api`**: la norma del proyecto exige que **toda ruta de negocio** vaya bajo `/api/**` (ruta objetivo: `/api/auth/**`, `/api/usuarios/**`, `/api/inventario/**`, `/api/reservas/**`, `/api/boletos/**`). Adoptarlo obliga a cambiar a la vez user-service, inventory-service, frontend y los 5 `contrato_api.md`. Detalle en `docs_ia/contexto/contrato_api.md` §5.
- **El rate limit es único (10/s, ráfaga 20) y con clave por IP**, no por `sub` del token; la especificación pide límites por ruta (5/s login, 20/s reservas, 50/s catálogo).
- **No hay `X-Request-Id` ni `traceparent`**: no hay trazas distribuidas entre el gateway y los servicios.
- **No hay revocación de tokens**: el filtro solo valida firma y expiración.
- **No hay `/actuator/prometheus`**: solo `/actuator/metrics`, `/actuator/info` y `/actuator/gateway`.

## No tocar

- `.mvn/`, `.idea/`, `target/` = build/IDE output.
