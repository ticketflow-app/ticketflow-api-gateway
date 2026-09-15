# Registro de conversaciones con IA

Bitácora de los prompts que el equipo comparte con el asistente de IA, con un resumen breve de qué respondió o qué cambió en el código. Cada entrada se agrega al final.

---

## 2026-09-13 — Prompt 1: ¿Por qué se importa WebFlux en el API Gateway?

### Prompt

> "Estaba teniendo una conversación sobre por qué se importa WebFlux en el API Gateway cuando ya de por sí Spring Cloud usa WebFlux internamente para ser reactivo."
>
> Se adjuntó documentación generada por Claude que explica el papel de WebFlux en Spring Cloud Gateway (comparación con Virtual Threads en los microservicios, tabla de tecnologías del gateway, `pom.xml`, rutas con `lb://` + Eureka, filtro JWT de ejemplo y orden de arranque con Docker Compose).

### Respuesta de la IA / acciones en el código

- **Aclaración conceptual:** WebFlux no se "importa" en el gateway: Spring Cloud Gateway **está construido sobre WebFlux + Reactor Netty de forma obligatoria**. No existe versión bloqueante clásica; la decisión de evitar WebFlux es solo para los 3 microservicios, que usan Virtual Threads. Un gateway solo enruta (poco trabajo por petición pero muchísimas conexiones abiertas), que es justo el caso de uso donde el event-loop de WebFlux brilla. Este matiz quedó documentado en `docs/documentacion_arquitectura.md`.
- **Starter correcto en Boot 4:** es `spring-cloud-starter-gateway-server-webflux` (reactivo, el que ya estaba en el proyecto). `spring-cloud-starter-gateway` es el nombre antiguo (pre-Boot 4) y `-server-mvc` sería la variante bloqueante, que no aplica aquí.
- **Descubrimiento de servicios (Eureka):** se agregó `spring-cloud-starter-netflix-eureka-client` + `spring-cloud-starter-loadbalancer`. Las rutas pasaron de URL fija (`http://localhost:8081`) a `lb://user-service` con fallback por variable de entorno (`${USER_SERVICE_URI:lb://user-service}`), para poder seguir desarrollando sin un eureka-server levantado.
- **Filtro JWT:** nuevo `JwtAuthFilter` (`GlobalFilter` + `Ordered`, orden `-1`) que valida `Authorization: Bearer <token>` con jjwt 0.12.6 (`jjwt-api`, `jjwt-impl`, `jjwt-jackson`). Salta rutas públicas (`/register`, `/login`, `/actuator`) y peticiones `OPTIONS`. El secreto se inyecta con `${JWT_SECRET:...}`.
- **Configuración:** se añadió el bloque `eureka.client` (con `EUREKA_ENABLED` para desactivarlo en dev, `EUREKA_URI` para la defaultZone) y `jwt.secret`.

### Archivos modificados

- `pom.xml`: +eureka-client, +loadbalancer, +jjwt.
- `src/main/resources/application.yml`: rutas `lb://` con fallback, bloque `eureka`, `jwt.secret`.
- `src/main/java/ticketflow/api_gateway/filter/JwtAuthFilter.java`: nuevo (filtro JWT global).
- `docs/ia/registro_conversaciones.md`: este registro.
- `docs/documentacion_arquitectura.md`: matiz sobre WebFlux en el gateway.
- `README.md`: stack, rutas y variables de entorno actualizadas.

### Nota

- `eureka-server` es un proyecto Spring Boot independiente (starter `spring-cloud-starter-netflix-eureka-server`) que corre en el puerto 8761; el gateway y los microservicios son **clientes** que se registran en él.
- El secreto JWT del gateway debe coincidir con el que firma los tokens en `user-service` (mínimo 32 bytes para HS256).
- Pendiente a futuro: alinear rutas con los servicios de la fase 1 (`usuarios-service`, `catalogo-service`, `inventario-service`) cuando dichos repos existan.