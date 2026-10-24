# Arquitectura — ticketflow-api-gateway

> **Alcance de este documento:** describe **exclusivamente la arquitectura del gateway**. La
> arquitectura de cada servicio destino está en su propio repositorio; aquí solo se nombran los
> destinos porque el enrutamiento es responsabilidad del gateway. No se describe el interior de
> ningún otro microservicio.

## 1. Propósito

El gateway es el **único punto de entrada HTTP** de la plataforma. Se encarga de:

1. **Enrutamiento** hacia los microservicios (vía Eureka o URL directa).
2. **Autenticación**: validación del JWT (HS256) y rechazo de peticiones sin token.
3. **Rate limiting** por IP, respaldado por Redis.
4. **Circuit breaker** con fallback, para degradar con elegancia cuando un destino falla.
5. **CORS** y **métricas** (`/actuator/*`).

No contiene lógica de negocio ni persiste datos.

- Contrato HTTP detallado (rutas, filtros, límites, desviaciones): `docs_ia/contexto/contrato_api.md`.
- Especificación de la plataforma: `docs/Microservicios.docx` (sección 2) y `docs/Arquitectura.docx` (secciones 2 y 6), en la raíz del workspace.

## 2. Identidad y despliegue

| Campo | Valor |
|---|---|
| Nombre de aplicación | `api-gateway` (se registra en Eureka con ese nombre) |
| Puerto | `8080` (`server.port`) |
| Prefijo de rutas | **`/api/` obligatorio** (norma del proyecto); hoy no se aplica (§5) |
| Base de datos | ninguna |
| Paquete base | `ticketflow.api_gateway` (con underscore, fuera de lo estándar de Java) |
| Starter de gateway | `spring-cloud-starter-gateway-server-webflux` |
| Configuración | `spring.cloud.gateway.server.webflux.*` (en Gateway 5 el prefijo cambió) |
| Docker | imagen en el compose del gateway, `dockerfile` en minúscula |

### 2.1 Estándar de versiones

Versiones **obligatorias y uniformes para todo el proyecto**. Este repositorio solo declara las
tecnologías que **usa**; las de los demás servicios están en sus respectivos `contrato_api.md`.

| Tecnología | Versión estándar | Versión actual | Estado |
|---|---|---|---|
| Java | **25 (LTS)** | 21 | ⚠️ Pendiente de migración |
| Spring Boot | **4.1.1** | 4.1.1 | ✔ Al día |
| Spring Cloud | **2025.1.3** | 2025.1.3 | ✔ Al día |
| Spring Cloud Gateway | **5.0.x** (WebFlux) | 5.0.x (BOM) | ✔ Al día |
| Redis | **8.x** | **7** (`redis:7-alpine`) | ⚠️ Pendiente (imagen del compose) |
| jjwt | **0.13.0** | **0.12.6** | ⚠️ Pendiente (divergencia con el emisor del token) |
| Resilience4j | **2.x** (Spring Cloud CircuitBreaker) | 2.x (BOM) | ✔ Al día |

> Este servicio **no** usa PostgreSQL, Kafka ni Flyway: no aparecen en su stack.

Clases del módulo (4 en total):

| Clase | Responsabilidad |
|---|---|
| `ApiGatewayApplication` | Punto de entrada Spring Boot |
| `filter/JwtAuthFilter` | Validación del JWT y rutas públicas |
| `config/RateLimitConfig` | Bean `ipKeyResolver` del rate limiter |
| `fallback/FallbackController` | Respuestas de degradación (`/fallback/users`, `/fallback/inventory`) |

## 3. Estilo arquitectónico

Componente de **edge (API Gateway)**: no aplica arquitectura hexagonal; es una capa de infraestructura
transversal al espacio de servicios. La lógica de negocio vive íntegramente en los destinos
(`ticketflow-user-service`, `ticketflow-inventory-service`).

Dependencias permitidas:

- Spring Cloud Gateway (WebFlux) — enrutamiento reactivo.
- Spring Cloud Netflix Eureka Client + Spring Cloud LoadBalancer — descubrimiento y balanceo.
- Spring Cloud CircuitBreaker (Resilience4j reactor) — circuit breaker y rate limiter.
- Spring Data Redis Reactive — contadores del rate limiter.
- Spring Boot Actuator — health, info, métricas y endpoint `gateway`.
- jjwt (`api`/`impl`/`jackson` **0.13.0**; hoy 0.12.6) — validación del token.

## 4. Pipeline de una petición

```text
Petición
  │
  ├─ 1. JwtAuthFilter          → ¿hay Bearer? ¿ruta pública?  → 401 si no
  ├─ 2. Global CORS            → preflight OPTIONS, origen http://localhost:8081
  ├─ 3. Route predicate (Path) → ¿coincide con alguna ruta?   → 404 si no
  ├─ 4. CircuitBreaker         → destino caído/lento          → 503 (forward:/fallback/...)
  ├─ 5. RequestRateLimiter     → cuota por IP                 → 429
  └─ 6. LoadBalancer (lb://)   → instancia concreta en Eureka
```

Orden real de los filtros: el `JwtAuthFilter` es un `WebFilter` registered con orden por defecto, por
lo que se ejecuta **antes** del enrutamiento del gateway; el CORS global se aplica en el
`HandlerMapping`.

## 5. Tabla de rutas

### 5.1 Implementado (rutas actuales)

| Ruta | Destino | Autenticación | Filtros |
|---|---|---|---|
| `/register`, `/login`, `/me`, `/users/**` | `${USER_SERVICE_URI:lb://ticketflow-user-service}` | pública solo `/register` y `/login` | `CircuitBreaker(userCircuitBreaker)` → `forward:/fallback/users`; `RequestRateLimiter` (10/s, ráfaga 20, clave por IP) |
| `/events/*/availability`, `/reservations`, `/reservations/**` | `${INVENTORY_SERVICE_URI:lb://ticketflow-inventory-service}` | exige Bearer | `CircuitBreaker(inventoryCircuitBreaker)` → `forward:/fallback/inventory`; `RequestRateLimiter` (10/s, ráfaga 20, clave por IP) |

### 5.2 Objetivo (con prefijo `/api/`)

> **Norma del proyecto:** todo endpoint de negocio enrutado por el gateway **debe** vivir bajo el
> prefijo **`/api/`**. La tabla completa, con la correspondencia entre rutas actuales y objetivo,
> está en `docs_ia/contexto/contrato_api.md` §5.

| Ruta objetivo | Destino | Autenticación |
|---|---|---|
| `/api/auth/**`, `/api/usuarios/**` | `lb://ticketflow-user-service` | Pública solo login, register, refresh |
| `/api/reservas/**`, `/api/boletos/**`, `/api/inventario/**` | `lb://ticketflow-inventory-service` | JWT |

La migración obliga a cambiar **en el mismo despliegue** los `Path` de `application.yml`, la lista
de rutas públicas del `JwtAuthFilter`, las rutas de los servicios destino y el BFF del frontend.

Endpoints propios:

| Ruta | Descripción |
|---|---|
| `/actuator/health`, `/actuator/info`, `/actuator/metrics` | Salud, información y métricas |
| `/actuator/gateway` | Rutas y filtros configurados (solo lectura) |
| `/fallback/users`, `/fallback/inventory` | Respuesta 503 cuando el circuit breaker está abierto |

## 6. Autenticación

- `JwtAuthFilter` (filtro global de WebFlux) valida el token con el secreto `jwt.secret`
  (`${JWT_SECRET:...}`, **≥32 bytes**, HS256).
- **Rutas públicas**: `/register`, `/login`, `/actuator` (por prefijo) y cualquier `OPTIONS`
  (preflight CORS).
- El resto de rutas exige `Authorization: Bearer <token>`; sin token válido responde `401` con
  cuerpo vacío.
- El gateway **no** emite tokens: los emite `ticketflow-user-service`. El secreto debe coincidir
  entre ambos servicios.

## 7. Resiliencia y límites

| Mecanismo | Configuración | Efecto |
|---|---|---|
| Circuit breaker | ventana deslizante 20, umbral de fallo 50 %, 10 s en `OPEN`, transición automática a `HALF_OPEN` | Si el destino falla, las siguientes peticiones van al `fallback` (503) sin esperar al timeout |
| Timeout | `timelimiter` 3 s | Corta la llamada lenta y dispara el fallback |
| Rate limiter | Redis token bucket: `replenishRate` 10/s, `burstCapacity` 20, `requestedTokens` 1, clave por IP | `429` al exceder la cuota |

> Sin Redis levantado, las rutas con `RequestRateLimiter` **fallan**: el rate limiter es
> obligatorio para el funcionamiento del gateway.

## 8. CORS

- `spring.cloud.gateway.server.webflux.globalcors` con `add-to-simple-url-handler-mapping: true`.
- Origen permitido: `http://localhost:8081`; métodos `GET, POST, PUT, PATCH, DELETE, OPTIONS`;
  cabeceras `Authorization, Content-Type`; `allow-credentials: true`.
- El frontend real corre en `:3000` y **no** lo consume el navegador: su BFF de Next.js reenvía
  desde el servidor. Ver `ticketflow-frontend/docs_ia/contexto/contrato_api.md`.

## 9. Descubrimiento de servicios

- Cliente Eureka: `eureka.client.enabled=${EUREKA_ENABLED:true}`,
  `service-url.defaultZone=${EUREKA_URI:http://localhost:8761/eureka}`,
  `prefer-ip-address: true`.
- `lb://<nombre>` se resuelve con Spring Cloud LoadBalancer.
- Para desarrollo sin Eureka: `EUREKA_ENABLED=false` y `USER_SERVICE_URI` /
  `INVENTORY_SERVICE_URI` con la URL directa (`http://localhost:8081`, `http://localhost:8082`).

## 10. Observabilidad

| Endpoint | Contenido |
|---|---|
| `/actuator/health` | Salud del gateway y sus dependencias |
| `/actuator/info` | Información de la aplicación |
| `/actuator/metrics` | Métricas de Micrometer |
| `/actuator/gateway` | Definición de rutas y filtros (`access: read-only`) |

> La especificación de plataforma pide `/actuator/prometheus`: **no está habilitado** (no hay
> registro de Prometheus). Es una desviación registrada en `contrato_api.md`.

## 11. Contrato con la especificación de plataforma

Resumen de lo que difiere entre este servicio y la sección 2 de `docs/Microservicios.docx`:

- **Sin prefijo `/api/**`** en las rutas: la norma del proyecto lo exige (§5.2).
- Solo hay 2 destinos; la especificación define 8 (los otros 6 todavía no existen).
- Rate limit único (10/s) en lugar de límites diferenciados por ruta (5/s en login, 20/s en
  reservas, 50/s en catálogo).
- Sin cabeceras de correlación (`X-Request-Id`, `traceparent`) ni de identidad
  (`X-User-Id`, `X-User-Roles`).
- **Java 21** en lugar del estándar del proyecto (**25 LTS**).
- **jjwt 0.12.6** frente al estándar del proyecto (**0.13.0**).
- **Redis 7** frente al estándar del proyecto (**8.x**).

El detalle completo está en `docs_ia/contexto/contrato_api.md` (sección 8).
