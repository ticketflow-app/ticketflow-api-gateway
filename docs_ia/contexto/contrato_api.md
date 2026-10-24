# Contrato de API — ticketflow-api-gateway

## 1. Propósito y estado

Define el **punto de entrada** de la plataforma: qué rutas enruta, con qué filtros, con qué límites de tasa y con qué política de autenticación.

- **Fuentes de verdad del contrato:**
  - Especificación de plataforma: `docs/Microservicios.docx` (sección 2 «api-gateway») y `docs/Arquitectura.docx` (secciones 2 y 6), copiadas en el workspace raíz.
  - Implementación real: `src/main/resources/application.yml`, `filter/JwtAuthFilter`, `config/RateLimitConfig`, `fallback/FallbackController`.
- **Alcance:** endpoints propios del gateway y tabla de enrutamiento hacia los demás servicios. Los contratos de cada servicio destino están en su propio `docs_ia/contexto/contrato_api.md`.
- **Estado:** vigente. Las desviaciones respecto de la especificación están listadas en la sección 8.

## 2. Identidad del servicio

| Campo | Implementado | Contrato (spec) | Nota |
|---|---|---|---|
| Nombre en Eureka | `api-gateway` | `api-gateway` | Coincide. |
| Puerto | **8080** | 8080 | Coincide. |
| Prefijo de rutas | **sin prefijo** — **no cumple** | **`/api/` obligatorio** | Ver sección 5. |
| Base de datos | ninguna | ninguna | El gateway no persiste nada. |
| Stack | Java 21, Spring Boot 4.1.1, Spring Cloud 2025.1.3, Spring Cloud Gateway (WebFlux) | Java 25 LTS, Spring Boot 4.1.1, Spring Cloud Gateway 5.0.x | La spec lo describe como reactivo: coincide. |
| Clave de configuración | `spring.cloud.gateway.server.webflux.*` | Igual | En Gateway 5 el prefijo cambió; una config antigua no carga las rutas. |
| Variables de entorno | `JWT_SECRET`, `EUREKA_URI`, `EUREKA_ENABLED`, `REDIS_HOST`, `REDIS_PORT`, `USER_SERVICE_URI`, `INVENTORY_SERVICE_URI` | `EUREKA_URL`, `REDIS_HOST`, `KAFKA_BOOTSTRAP` | Nombres propios del repositorio. |

## 3. Estándar de versiones

Versiones **obligatorias y uniformes para todo el proyecto**. Solo se listan aquí las tecnologías
que **este servicio usa**; las de los demás repos están en sus respectivos `contrato_api.md`.

| Tecnología | Versión estándar | Versión actual en el repo | Estado |
|---|---|---|---|
| Java | **25 (LTS)** | 21 | ⚠️ Pendiente de migración |
| Spring Boot | **4.1.1** | 4.1.1 | ✔ Al día |
| Spring Cloud | **2025.1.3** | 2025.1.3 | ✔ Al día |
| Spring Cloud Gateway | **5.0.x** (WebFlux) | 5.0.x (BOM de Spring Cloud) | ✔ Al día |
| Redis | **8.x** | **7** (`redis:7-alpine`) | ⚠️ Pendiente (imagen del compose) |
| jjwt | **0.13.0** | **0.12.6** | ⚠️ Pendiente (divergencia con `ticketflow-user-service`) |
| Resilience4j | **2.x** (Spring Cloud CircuitBreaker) | 2.x (BOM) | ✔ Al día |

> Este servicio **no** usa PostgreSQL, Kafka ni Flyway: no aparecen en su stack. PostgreSQL lo usan
> `ticketflow-user-service` y `ticketflow-inventory-service`; Kafka, solo
> `ticketflow-inventory-service`.

## 4. Convenciones de API

| Concepto | Implementado | Contrato (spec) |
|---|---|---|
| Autenticación | `Authorization: Bearer <JWT>` validado por `JwtAuthFilter` (HS256, secreto compartido) | Igual, con validación de `jti` contra `auth:revoked:*` |
| **Prefijo de rutas** | **sin prefijo** — **no cumple** | **`/api/` obligatorio** (ver sección 5) |
| Rutas públicas | `/register`, `/login`, `/actuator*` y todo `OPTIONS` (preflight) | `/api/auth/login`, `/api/auth/register`, `/api/auth/refresh` |
| Cabeceras inyectadas | ninguna | `X-User-Id`, `X-User-Roles` para trazas |
| Correlación | ninguna | `X-Request-Id` generado por el gateway, `traceparent` propagado |
| Formato de error | JSON libre del `FallbackController` | RFC 9457 `application/problem+json` (503 en fallback) |
| CORS | `allowed-origins: http://localhost:8081` | Frontend real en `:3000` (el frontend usa BFF server-side) |
| Límite de tasa | Redis token bucket, 10 req/s con ráfaga 20 en **todas** las rutas | 5/s en login, 50/s en catálogo, 20/s ráfaga 40 en reservas |
| Circuit breaker | Ventana 20, umbral 50 %, 10 s en `OPEN` con paso automático a `HALF_OPEN`; timeout 3 s | Igual (ventana 20, 50 %, 10 s) |
| Health | `/actuator/health` | `/actuator/health/liveness` y `/readiness` |
| Métricas | `/actuator/metrics`, `/actuator/gateway` (solo lectura) | `/actuator/prometheus` |

## 5. Prefijo de rutas `/api/` (obligatorio)

> **Norma del proyecto:** todo endpoint de negocio enrutado por este gateway **debe** vivir bajo
> el prefijo **`/api/`**. Se exceptúan los endpoints de infraestructura (`/actuator/**`) y los
> `forward:` internos de fallback, que no son rutas de negocio.

### 5.1 Tabla de enrutamiento objetivo (con prefijo `/api/`)

Esta es la tabla de rutas que el gateway **debe** exponer. Los destinos marcados como
*(no implementado)* son servicios de la especificación que aún no existen en el workspace.

| Ruta (objetivo) | Destino | Autenticación | Filtros |
|---|---|---|---|
| `/api/auth/**`, `/api/usuarios/**` | `lb://ticketflow-user-service` | Pública solo login, register, refresh | `RequestRateLimiter` 5/s (login), `CircuitBreaker` |
| `GET /api/eventos/**`, `/api/recintos/**` | `lb://catalogo-service` *(no implementado)* | Pública (lectura) | `RequestRateLimiter` 50/s, `CircuitBreaker` con fallback |
| `POST|PUT /api/eventos/**`, `/api/organizador/**` | `lb://catalogo-service` *(no implementado)* | JWT, rol `ORGANIZADOR` | `CircuitBreaker` |
| `/api/reservas/**`, `/api/boletos/**`, `/api/inventario/**` | `lb://ticketflow-inventory-service` | JWT | `RequestRateLimiter` 20/s ráfaga 40, `CircuitBreaker` (sin reintentos en POST) |
| `/api/checkin/**` | `lb://checkin-service` *(no implementado)* | JWT, rol `STAFF_CHECKIN` o `ADMIN` | Timeout corto 1 s |
| `/api/notificaciones/**` | `lb://notificaciones-service` *(no implementado)* | JWT | `CircuitBreaker` |
| `/api/resenas/**` | `lb://resenas-service` *(no implementado)* | GET público; POST con JWT | `CircuitBreaker` |
| `/api/analitica/**` | `lb://analitica-service` *(no implementado)* | JWT, rol `ORGANIZADOR` o `ADMIN` | `CircuitBreaker` |

### 5.2 Correspondencia con las rutas implementadas

| Ruta objetivo | Ruta actual | Servicio destino |
|---|---|---|
| `POST /api/auth/register` | `POST /register` | user-service |
| `POST /api/auth/login` | `POST /login` | user-service |
| `GET /api/usuarios/me` | `GET /me` | user-service |
| `PATCH /api/usuarios/{id}/rol` | `PATCH /users/{id}/rol` | user-service |
| `GET /api/inventario/eventos/{eventoId}/disponibilidad` | `GET /events/*/availability` | inventory-service |
| `POST /api/reservas` | `POST /reservations` | inventory-service |
| `GET /api/reservas/{id}` | `GET /reservations/**` | inventory-service |

### 5.3 Impacto de la migración al prefijo `/api/`

Al migrar hay que cambiar **en el mismo despliegue**:

1. Los `Path` de las rutas en `src/main/resources/application.yml`.
2. La lista de rutas públicas del `JwtAuthFilter` (que hoy marca `/register` y `/login`).
3. Las rutas de los controladores de `ticketflow-user-service` y `ticketflow-inventory-service`
   (ver sus `contrato_api.md`, sección 5).
4. El BFF del frontend (`ticketflow-frontend/src/app/api/gateway/[...path]/route.ts`) y sus rutas.
5. Este documento, el del user-service, el del inventory-service y el del frontend.

> **No hay versión de compatibilidad**: se migra de golpe (cambio incompatible). Si fuera necesario
> mantener las rutas viejas durante una transición, deben convivir con el prefijo `/api/` y
> marcarse como obsoletas en un `DA-xxx`; nunca sustituir `/api/**` por rutas sin prefijo.

## 6. Endpoints propios del gateway

| Método | Ruta | Rol | Descripción | Respuestas |
|---|---|---|---|---|
| GET | `/actuator/health` | Público | Salud del gateway. | 200 |
| GET | `/actuator/info`, `/actuator/metrics` | Público | Métricas y estado. | 200 |
| GET | `/actuator/gateway` | Público | Rutas y filtros configurados (solo lectura). | 200 |
| ANY | `/fallback/users` | Público | Respuesta de degradación cuando el circuit breaker de Usuarios está abierto. | 503 |
| ANY | `/fallback/inventory` | Público | Respuesta de degradación cuando el circuit breaker de Inventario está abierto. | 503 |
| ANY | *(todas las rutas de la tabla 7)* | Según la ruta | Enrutamiento a los servicios. | according al destino; 401 sin token, 429 por tasa, 503 por circuit breaker abierto |

## 7. Tabla de enrutamiento

### 7.1 Implementado

| Ruta | Destino | Autenticación | Filtros |
|---|---|---|---|
| `/register`, `/login`, `/me`, `/users/**` | `lb://ticketflow-user-service` (o `${USER_SERVICE_URI}` para URL directa) | Pública solo `/register` y `/login` | `CircuitBreaker` (`userCircuitBreaker` → `forward:/fallback/users`) + `RequestRateLimiter` (10/s, ráfaga 20, clave por IP) |
| `/events/*/availability`, `/reservations`, `/reservations/**` | `lb://ticketflow-inventory-service` (o `${INVENTORY_SERVICE_URI}`) | Requiere Bearer | `CircuitBreaker` (`inventoryCircuitBreaker` → `forward:/fallback/inventory`) + `RequestRateLimiter` (10/s, ráfaga 20, clave por IP) |

### 7.2 Contrato de la especificación

Ver la tabla 5.1: es la versión con prefijo `/api/` de las rutas de la especificación.

## 8. Desviaciones respecto de la especificación

| # | Tema | Especificación | Implementado | Impacto |
|---|---|---|---|---|
| 1 | **Prefijo de rutas** | Todo bajo `/api/**` | Rutas en la raíz (`/me`, `/users/**`, `/reservations`) | **Incumple la norma del proyecto** (sección 5). Adoptar `/api/**` exige cambiar a la vez gateway, user-service, inventory-service, frontend y los 4 `contrato_api.md`. |
| 2 | Nombres de servicio destino | `usuarios-service`, `inventario-service` | `ticketflow-user-service`, `ticketflow-inventory-service` | Debe mantenerse sincronizado con Eureka. |
| 3 | Límites de tasa | Diferenciados por ruta (5/s, 20/s, 50/s) | 10/s con ráfaga 20 en todas | No se cumple el límite de 5/s en login (protección contra fuerza bruta). |
| 4 | Clave del limitador | Por usuario (`sub`) o IP si es anónimo | `@ipKeyResolver` (siempre por IP) | Usuarios detrás de la misma IP comparten cuota. |
| 5 | Cabeceras de identidad | `X-User-Id`, `X-User-Roles` | No se inyectan | Los servicios destino deben leer el token por su cuenta. |
| 6 | Correlación | `X-Request-Id` + `traceparent` | No implementada | Sin trazas distribuidas entre gateway y servicios. |
| 7 | Revocación de tokens | `jti` contra `auth:revoked:*` en Redis | Solo se valida firma y expiración | Un token revocado sigue siendo válido hasta expirar. |
| 8 | Formato de error | RFC 9457 | JSON libre en el `FallbackController` | Inconsistente con el resto de la plataforma. |
| 9 | **Java** | **25 LTS** | **21** | Sin hilos virtuales. |
| 10 | Spring Boot | 4.1.1 | 4.1.1 | ✔ Al día |
| 11 | **jjwt** | **0.13.0** | **0.12.6** | Divergencia con user-service. |
| 12 | **Redis** | **8.x** | **7** | La imagen del compose no es la fijada como estándar del proyecto. |
| 13 | Servicios destino | 8 microservicios | 2 (user, inventory) | El resto de rutas de la spec no existen todavía. |
| 14 | Servicios ausentes en el gateway | catálogo, checkin, notificaciones, reseñas, analítica | No hay rutas | Se añaden al crear cada servicio. |
| 15 | Métricas | `/actuator/prometheus` | `/actuator/metrics` | Falta el registro de Prometheus para el despliegue en AWS. |

## 9. Cómo mantener este documento

1. Cambiar una ruta, un filtro o un límite de tasa implica actualizar las secciones 6 y 7 en el mismo commit.
2. Cambiar el prefijo o el enrutamiento objetivo implica actualizar la sección 5 y los contratos de los servicios destino.
3. Añadir un servicio nuevo implica añadir su fila en 5.1 y su ruta real en 7.1.
4. La especificación de la plataforma vive fuera de este repositorio (`docs/Microservicios.docx` en la raíz del workspace); aquí solo se refleja su efecto en este servicio.
