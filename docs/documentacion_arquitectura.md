# Documentación de arquitectura

## Plataforma de venta de boletos para eventos de alta demanda

**Fase 1 - Microservicios: Usuarios, Catálogo y búsqueda, Inventario**

## 1. Visión general

Esta fase inicial del sistema cubre los tres microservicios base sobre los cuales se construirán las funcionalidades restantes (notificaciones, check-in, analítica). Los tres servicios comparten un mismo entorno de contenedores y quedan expuestos al cliente a través de un único punto de entrada (API Gateway), que también actúa como balanceador de carga.

El diseño separa deliberadamente los servicios según su patrón de acceso a datos: Usuarios y Catálogo son mayormente de lectura y toleran cierta latencia de sincronización (por lo que se apoyan en caché); Inventario, en cambio, requiere consistencia inmediata porque gestiona un recurso compartido y limitado (la disponibilidad de boletos), por lo que su fuente de verdad es un contador atómico en Redis en vez de una caché tradicional.

## 2. Stack tecnológico

| Componente | Tecnología | Justificación |
|---|---|---|
| Lenguaje / framework de los servicios | Java + Spring Boot | Ecosistema maduro para microservicios; Spring Security, Spring Data y Spring Cloud Gateway cubren la mayoría de necesidades sin librerías externas |
| Estilo arquitectónico interno | Arquitectura hexagonal (puertos y adaptadores) | El dominio queda aislado de Spring, JPA, Redis y Kafka; permite probar la lógica de negocio sin infraestructura real |
| Base de datos relacional | PostgreSQL (una instancia lógica por servicio) | Consistencia transaccional fuerte; cada servicio es dueño exclusivo de su propio esquema |
| Caché y contadores atómicos | Redis | Operaciones atómicas de un solo hilo (INCR/DECR), ideal tanto para caché de lectura como para control de concurrencia |
| Mensajería asíncrona | Apache Kafka (modo KRaft) | Múltiples consumidores independientes (Notificaciones, Analítica) leyendo el mismo evento, con capacidad de replay |
| Concurrencia dentro del servicio | Virtual Threads (Java 21 / Spring Boot 3.2+) | Maneja miles de peticiones I/O-bound (esperando Redis/Postgres) sin el costo de programación reactiva de WebFlux |
| Registro de servicios | Eureka (Spring Cloud Netflix) | Descubrimiento dinámico de instancias; muy relevante para roles Java/Spring en el mercado |
| Autenticación | JWT (Spring Security) | Permite validar la identidad del usuario en cada servicio sin llamar a Usuarios en cada petición |
| API Gateway / balanceo de carga | Spring Cloud Gateway | Punto único de entrada, enrutamiento hacia los servicios; integra rate limiting con Redis |
| CDN + DNS | Cloudflare | Sirve assets estáticos (imágenes de eventos, frontend) cerca del usuario; nivel gratuito generoso |
| Contenedores (desarrollo) | Docker + Docker Compose | Entorno reproducible para desarrollo y pruebas de carga |
| Orquestador (curso) | AWS ECS + Fargate | Contenedores sin gestionar servidores; menor curva de aprendizaje para el alcance del curso |
| Orquestador (evolución post-curso) | AWS EKS + Fargate | Kubernetes real y portable a cualquier nube; alto valor de mercado, se añade una vez el MVP funcione |
| Pruebas de integración | Testcontainers | Postgres, Redis y Kafka reales durante las pruebas automatizadas, no mocks |

## 3. Decisiones técnicas descartadas

| Tecnología considerada | Decisión | Motivo |
|---|---|---|
| Netflix Zuul (API Gateway) | Descartado a favor de Spring Cloud Gateway | Zuul 1 está deprecado por Netflix; Spring Cloud Gateway es su reemplazo oficial dentro del ecosistema Spring |
| Kong (API Gateway) | Pospuesto como mejora post-curso | Alternativa real y vigente, pero agnóstica de lenguaje; agrega una herramienta de infraestructura nueva que no aporta al alcance actual del curso |
| Apache httpd (balanceo de carga) | Descartado a favor de AWS ALB | Un balanceador autogestionado no enseña nada adicional frente al ALB administrado, y suma trabajo operativo (parches, escalado manual) sin beneficio |
| Spring WebFlux (concurrencia) | Descartado a favor de Virtual Threads | WebFlux exige reescribir toda la cadena en modo no bloqueante (R2DBC, Mono/Flux) y complica el debugging; Virtual Threads da el mismo beneficio de I/O concurrente con código bloqueante normal |

**Nota sobre WebFlux — aclaración importante:** la decisión de descartar WebFlux **aplica únicamente a los tres microservicios**, no al API Gateway. El Gateway está construido sobre WebFlux + Reactor Netty de forma **obligatoria**: no existe versión bloqueante de Spring Cloud Gateway, y ese modelo reactivo es además el correcto para su función — un gateway solo enruta (poquísimo trabajo por petición) pero debe sostener muchísimas conexiones concurrentes abiertas, que es exactamente el caso de uso donde un event-loop no bloqueante brilla. Los microservicios, en cambio, sí hacen trabajo real por petición (consultar Redis, escribir en Postgres), por lo que se benefician más de la simplicidad de Virtual Threads. En el Gateway apenas se escribe código reactivo propio: las rutas se configuran declarativamente en YAML y el único código `Mono`/`Flux` es el filtro JWT, que es un patrón mecánico (seguir o cortar con `chain.filter()` / `setComplete()`). WebFlux sí tendría además una ventaja real en los servicios si en una fase posterior se quiere transmitir la disponibilidad de boletos en vivo al cliente (Server-Sent Events); se reevaluará puntualmente para ese caso.

## 4. Diagrama de arquitectura (microservicios)

El diagrama muestra el flujo de una petición desde el cliente hasta el almacenamiento de datos de cada servicio. Catálogo combina PostgreSQL (datos persistentes) con Redis como caché de lectura. Inventario usa Redis como fuente de verdad para la disponibilidad en tiempo real, PostgreSQL como registro histórico/auditable, y publica en Kafka el evento de reserva confirmada, que en una fase posterior consumirán Notificaciones y Analítica de forma independiente.

```
Cliente (frontend)
   │
   ▼
API Gateway (Spring Cloud Gateway) ──── rate limiting (Redis)
   │
   ├──▶ Usuarios ──▶ PostgreSQL
   │
   ├──▶ Catálogo y búsqueda ──▶ PostgreSQL + Redis (caché de lectura)
   │
   └──▶ Inventario ──▶ Redis (fuente de verdad) + PostgreSQL (histórico)
                            │
                            └──▶ Kafka (topic reservas.confirmadas) ──▶ Notificaciones / Analítica
```

## 5. Arquitectura hexagonal por microservicio

Cada uno de los tres microservicios se construye siguiendo el patrón de puertos y adaptadores: el dominio (entidades y reglas de negocio) no tiene ninguna dependencia de Spring, JPA, Redis ni Kafka. La aplicación define los casos de uso y los puertos (interfaces) que necesita; la infraestructura implementa esos puertos mediante adaptadores concretos.

Estructura de paquetes de referencia (ejemplo para Inventario):

```
inventario-service/
├── domain/
│   ├── Reserva.java
│   └── EstadoReserva.java
├── application/
│   ├── port/
│   │   ├── in/
│   │   │   └── ReservarBoletosUseCase.java
│   │   └── out/
│   │       ├── ReservaRepositoryPort.java
│   │       ├── DisponibilidadPort.java
│   │       └── EventoPublisherPort.java
│   └── service/
│       └── ReservarBoletosService.java
└── infrastructure/
    ├── adapter/
    │   ├── in/web/
    │   │   └── ReservaController.java
    │   └── out/
    │       ├── persistence/
    │       │   └── ReservaJpaAdapter.java
    │       ├── cache/
    │       │   └── RedisDisponibilidadAdapter.java
    │       └── messaging/
    │           └── KafkaEventoPublisherAdapter.java
```

Esta separación permite probar la lógica de negocio (por ejemplo, "no reservar si no hay disponibilidad") con pruebas unitarias puras, sin levantar Spring, Redis ni PostgreSQL, y facilita reemplazar cualquier adaptador (por ejemplo, cambiar Redis por otra tecnología de caché) sin tocar el dominio.

## 6. Mensajería: Kafka

Se eligió Apache Kafka sobre RabbitMQ para la comunicación asíncrona entre servicios. La justificación técnica:

| Criterio | Kafka | RabbitMQ |
|---|---|---|
| Modelo | Log distribuido; el mensaje persiste y puede leerse múltiples veces | Cola de tareas; el mensaje desaparece al ser consumido |
| Múltiples consumidores independientes | Nativo (consumer groups con su propio offset) | Requiere fanout exchange; sin replay |
| Replay de eventos históricos | Sí (se puede reprocesar desde cualquier offset) | No |
| Complejidad operativa | Mayor (mitigada con modo KRaft, sin Zookeeper) | Menor |

El caso de uso concreto: cuando Inventario confirma una reserva, tanto Notificaciones como, en una fase posterior, Analítica necesitan enterarse del mismo evento, cada uno a su propio ritmo. Ese patrón de "un evento, múltiples lectores independientes" es el caso de uso característico de Kafka.

Topic principal: `reservas.confirmadas` — publicado por Inventario; consumido por Notificaciones (fase 1) y Analítica (fase 2).

## 7. Componentes adicionales de resiliencia y observabilidad

| Necesidad | Herramienta | Por qué importa |
|---|---|---|
| Circuit breaker / reintentos | Resilience4j | Evita que una lentitud en Inventario tumbe el resto del sistema |
| Métricas | Spring Boot Actuator + Micrometer | Expone métricas en formato Prometheus automáticamente |
| Trazabilidad distribuida | Micrometer Tracing + Zipkin | Permite seguir una petición a través de los tres servicios |
| Rate limiting | Spring Cloud Gateway (Redis) | Protege contra bots reutilizando la infraestructura de Redis existente |
| Idempotencia | Clave de idempotencia en POST /reservations | Evita reservas duplicadas ante reintentos de red |
| Mensajes fallidos | Dead-letter topic en Kafka | Evita perder eventos que un consumidor no pudo procesar |

## 8. Arquitectura de despliegue en AWS

Se centraliza el despliegue en un único proveedor de nube (AWS) para evitar la complejidad innecesaria de manejar IAM, redes y facturación en múltiples nubes. La única excepción es Cloudflare, usado exclusivamente para CDN y DNS por su nivel gratuito más generoso que CloudFront.

| Capa | Servicio AWS | Nota |
|---|---|---|
| Cómputo | ECS Fargate | Contenedores sin gestionar servidores; menor overhead operativo que EKS/Kubernetes para este alcance |
| Balanceo | Application Load Balancer (ALB) | Frente a Spring Cloud Gateway, terminación TLS |
| Registro de servicios | Eureka (contenedor propio) | Descubrimiento dinámico de instancias dentro de la subred privada |
| Base de datos | RDS para PostgreSQL | Una instancia, un esquema por servicio |
| Caché / contador atómico | ElastiCache para Redis | — |
| Mensajería | Kafka propio en Docker/EC2 durante el curso | MSK (Kafka administrado) es la opción equivalente en producción; se omite por costo |
| Almacenamiento estático | S3 | Detrás del CDN |
| Secretos | Systems Manager Parameter Store | Credenciales de base de datos, secreto JWT |
| CI/CD | GitHub Actions -> ECR -> ECS | — |

### 8.1 ECS vs. EKS - por qué ECS para el curso

| Criterio | ECS + Fargate (elegido para el curso) | EKS + Fargate (evolución post-curso) |
|---|---|---|
| Curva de aprendizaje | Baja, configuración simple | Alta: Deployments, Services, Ingress, HPA |
| Portabilidad | Solo AWS | Estándar de la industria, portable a cualquier nube |
| Valor de mercado | Bueno, específico de AWS | Muy alto, la habilidad de orquestación más pedida |
| Tiempo para tenerlo funcionando | Rápido | Más lento |

Fargate es el motor de cómputo "sin servidores" y funciona igual con ECS o con EKS; la migración futura sugerida es ECS+Fargate → EKS+Fargate, conservando el mismo modelo serverless pero con Kubernetes real.

## 9. Red y seguridad (VPC)

- **Subred pública:** únicamente el Application Load Balancer y el NAT Gateway tienen ruta directa desde internet.
- **Subred privada:** los servicios en ECS Fargate, Eureka, RDS, ElastiCache y Kafka no tienen IP pública; solo son alcanzables desde dentro de la VPC.
- **NAT Gateway:** permite que los servicios en la subred privada salgan a internet (por ejemplo, para llamar a una API externa) sin exponerse a conexiones entrantes.
- **Security Groups:** actúan como firewall a nivel de servicio — por ejemplo, RDS solo acepta conexiones desde el Security Group de ECS, de nadie más.
- **IAM Roles:** cada tarea de ECS asume un rol propio con permisos mínimos (principio de menor privilegio), en vez de compartir credenciales entre servicios.

> Nota de costo: el NAT Gateway cobra por hora incluso sin tráfico; para un proyecto de curso conviene activarlo solo durante las pruebas de carga en vez de dejarlo corriendo permanentemente.

## 10. Descripción de los microservicios

### 10.1 Usuarios

Responsable del registro, autenticación y gestión de sesión de todos los actores del sistema (comprador, organizador, staff de check-in). Es la base de la que dependen los demás servicios para validar identidad y permisos.

**Modelo de datos:** `Usuario(id, nombre, email, password_hash, rol, creado_en)`

**Dependencias principales (Maven):** `spring-boot-starter-web`, `spring-boot-starter-security`, `spring-boot-starter-data-jpa`, `spring-boot-starter-validation`, `spring-boot-starter-actuator`, `spring-cloud-starter-netflix-eureka-client`, `io.jsonwebtoken:jjwt-api / jjwt-impl / jjwt-jackson`, `org.postgresql:postgresql`

**Endpoints principales:**

| Método | Ruta | Descripción |
|---|---|---|
| POST | /register | Crea un nuevo usuario y almacena la contraseña con hash (bcrypt/argon2) |
| POST | /login | Valida credenciales y emite un JWT firmado |
| GET | /me | Devuelve los datos del usuario autenticado a partir del token |
| PATCH | /users/:id/rol | Actualiza el rol de un usuario (uso administrativo) |

**Caso de uso - capa de aplicación (puerto de entrada + servicio):**

```java
public interface RegistrarUsuarioUseCase {
    UsuarioDTO registrar(RegistrarUsuarioCommand cmd);
}

@Service
public class RegistrarUsuarioService implements RegistrarUsuarioUseCase {
    private final UsuarioRepositoryPort repository;   // puerto de salida
    private final PasswordHasherPort hasher;          // puerto de salida

    public UsuarioDTO registrar(RegistrarUsuarioCommand cmd) {
        if (repository.existePorEmail(cmd.email()))
            throw new EmailYaRegistradoException(cmd.email());

        Usuario usuario = Usuario.crear(cmd.nombre(), cmd.email(),
                hasher.hash(cmd.password()), Rol.COMPRADOR);
        return UsuarioDTO.desde(repository.guardar(usuario));
    }
}
```

**Configuración (application.yml, fragmento clave):**

```yaml
spring:
  threads:
    virtual:
      enabled: true
  datasource:
    hikari:
      maximum-pool-size: 15
eureka:
  client:
    service-url:
      defaultZone: http://eureka-server:8761/eureka
jwt:
  secret: ${JWT_SECRET}   # inyectado desde Parameter Store
  expiration: 3600000
```

**Decisión de diseño clave:** se utiliza JWT en lugar de sesiones almacenadas en servidor. El token incluye el rol del usuario firmado digitalmente, de modo que Catálogo e Inventario pueden validar la identidad localmente (verificando la firma) sin necesidad de llamar a Usuarios en cada petición. Esto evita que Usuarios se convierta en un cuello de botella y en un punto único de falla para todo el sistema.

### 10.2 Catálogo y búsqueda

Permite a los organizadores crear y administrar eventos, y a los compradores consultarlos, filtrarlos y buscarlos. Contiene únicamente la configuración estática del evento; la disponibilidad de boletos en tiempo real vive en Inventario.

**Modelo de datos:** `Evento(id, organizador_id, nombre, descripción, fecha, lugar, categoría)` · `Seccion(id, evento_id, nombre, precio, capacidad_total)`

**Dependencias adicionales (Maven):** `spring-boot-starter-data-redis` (cliente Lettuce, compatible con Virtual Threads).

**Endpoints principales:**

| Método | Ruta | Descripción |
|---|---|---|
| POST | /events | El organizador crea un evento junto con sus secciones |
| GET | /events?fecha=&ciudad=&categoria= | Búsqueda y filtrado de eventos |
| GET | /events/:id | Detalle de un evento específico |
| PATCH | /events/:id | Edición de un evento existente |

El patrón cache-aside se implementa en el adaptador de salida, no con la anotación `@Cacheable` sobre el caso de uso — así el caso de uso no sabe que existe una caché:

```java
@Component
public class CacheAsideEventoAdapter implements EventoRepositoryPort {
    private final EventoJpaRepository jpaRepo;
    private final RedisTemplate<String, Evento> redis;

    public Optional<Evento> buscarPorId(Long id) {
        String key = "evento:" + id;
        Evento cacheado = redis.opsForValue().get(key);
        if (cacheado != null) return Optional.of(cacheado);  // cache hit

        Optional<Evento> desdeDb = jpaRepo.findById(id).map(EventoMapper::aDominio);
        desdeDb.ifPresent(e -> redis.opsForValue().set(key, e, Duration.ofSeconds(30)));
        return desdeDb;
    }

    public Evento guardar(Evento evento) {
        Evento guardado = EventoMapper.aDominio(jpaRepo.save(EventoMapper.aEntidad(evento)));
        redis.delete("evento:" + guardado.getId());  // invalidacion al editar
        return guardado;
    }
}
```

**Decisión de diseño clave:** se aplica el patrón cache-aside. En una consulta (GET) primero se revisa Redis; si el dato no está (cache miss), se consulta PostgreSQL y el resultado se guarda en caché con un TTL corto. Al editar un evento (PATCH) se invalida la entrada correspondiente. Este servicio tolera datos con unos segundos de desactualización sin que esto genere ningún problema para el usuario.

### 10.3 Inventario

Es la fuente de verdad de cuántos boletos quedan disponibles por sección y decide, de forma atómica, si una compra puede completarse o no. Es el servicio con mayor exigencia de consistencia de los tres.

**Modelo de datos:**
- Redis — `disponibilidad:{evento_id}:{seccion_id}` = entero (fuente en tiempo real)
- PostgreSQL — `Reserva(id, usuario_id, evento_id, seccion_id, cantidad, estado, idempotency_key, creado_en)` (registro histórico/auditable)

**Dependencias adicionales (Maven):** `spring-kafka`, `resilience4j-spring-boot3`.

**Endpoints principales:**

| Método | Ruta | Descripción |
|---|---|---|
| GET | /availability/:evento_id | Disponibilidad actual por sección (lectura directa desde Redis) |
| POST | /reservations | Operación crítica: procesa la compra/reserva de boletos (requiere header Idempotency-Key) |
| GET | /reservations/user/:id | Historial de compras de un usuario |

**Adaptador de salida - Redis como fuente atómica:**

```java
@Component
public class RedisDisponibilidadAdapter implements DisponibilidadPort {
    private final StringRedisTemplate redis;

    public long decrementar(Long eventoId, Long seccionId, int cantidad) {
        String key = "disponibilidad:%d:%d".formatted(eventoId, seccionId);
        return redis.opsForValue().decrement(key, cantidad); // DECRBY, atomico
    }

    public void revertir(Long eventoId, Long seccionId, int cantidad) {
        String key = "disponibilidad:%d:%d".formatted(eventoId, seccionId);
        redis.opsForValue().increment(key, cantidad); // INCRBY, compensacion
    }
}
```

**Caso de uso - orquesta disponibilidad, persistencia e idempotencia:**

```java
@Service
public class ReservarBoletosService implements ReservarBoletosUseCase {
    private final DisponibilidadPort disponibilidad;
    private final ReservaRepositoryPort reservas;
    private final EventoPublisherPort eventos;

    public ReservaDTO reservar(ReservarBoletosCommand cmd) {
        reservas.buscarPorIdempotencyKey(cmd.idempotencyKey())
                .ifPresent(r -> { throw new ReservaYaExistenteException(r); });

        long restante = disponibilidad.decrementar(cmd.eventoId(), cmd.seccionId(), cmd.cantidad());

        if (restante < 0) {
            disponibilidad.revertir(cmd.eventoId(), cmd.seccionId(), cmd.cantidad());
            throw new SinDisponibilidadException(cmd.eventoId(), cmd.seccionId());
        }

        Reserva reserva = Reserva.confirmar(cmd.usuarioId(), cmd.eventoId(),
                cmd.seccionId(), cmd.cantidad(), cmd.idempotencyKey());
        reservas.guardar(reserva);
        eventos.publicar(new ReservaConfirmadaEvent(reserva));  // -> Kafka
        return ReservaDTO.desde(reserva);
    }
}
```

**Configuración de resiliencia y mensajería (application.yml):**

```yaml
resilience4j:
  circuitbreaker:
    instances:
      catalogoClient:
        sliding-window-size: 20
        failure-rate-threshold: 50
        wait-duration-in-open-state: 10s
spring:
  kafka:
    bootstrap-servers: kafka:9092
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
  threads:
    virtual:
      enabled: true
```

**Flujo de una compra:**

1. El cliente envía la solicitud con un header `Idempotency-Key` generado por el cliente (UUID).
2. Si esa clave ya fue procesada antes, se devuelve el resultado guardado sin repetir la operación (evita duplicados por reintento de red).
3. Inventario ejecuta un `DECRBY` atómico sobre el contador correspondiente en Redis.
4. Si el resultado es mayor o igual a cero, la compra se acepta, se registra la reserva en PostgreSQL y se publica el evento en Kafka.
5. Si el resultado es negativo, la operación se revierte (`INCRBY`) y se responde que no hay disponibilidad.

**Decisión de diseño clave:** se usa Redis en lugar de bloqueos a nivel de fila (`SELECT ... FOR UPDATE`) en la base de datos relacional. Bajo concurrencia extrema, los locks de base de datos generan contención (muchas conexiones esperando el mismo lock). Redis, al ser de un solo hilo, ejecuta operaciones atómicas sin ese problema y soporta un throughput mucho mayor. PostgreSQL se conserva como registro durable y auditable, pero la decisión de disponibilidad ocurre siempre en Redis. El breve instante en que el contador puede quedar negativo antes de revertirse no genera sobreventa, porque cualquier otra petición concurrente que decremente durante esa ventana también verá un resultado negativo y fallará correctamente.

## 11. Comunicación entre servicios

- **Usuarios** es la base de autenticación: los demás servicios validan el JWT localmente, sin llamarlo en cada petición.
- **Catálogo → Inventario:** al publicar un evento, Catálogo notifica a Inventario para inicializar los contadores de disponibilidad de cada sección (llamada REST síncrona en esta fase; puede evolucionar a un evento asíncrono más adelante).
- **Inventario → Kafka:** al confirmar una reserva, Inventario publica el evento en el topic `reservas.confirmadas`, que consumirán Notificaciones y, en una fase posterior, Analítica.

Flujo típico del comprador: `Cliente → Catálogo (explora y elige evento/sección) → Inventario (compra)`.

## 12. Estrategia de caché

| Operación | Estrategia | Justificación |
|---|---|---|
| Explorar catálogo | Cacheable (Redis, TTL corto) | Alta proporción de lecturas frente a escrituras; tolera datos con pocos segundos de desactualización |
| Comprar boleto | Sin caché - contador atómico | Requiere consistencia inmediata para evitar sobreventa del mismo boleto |

## 13. Despliegue

En desarrollo y para las pruebas de carga, los tres servicios, sus bases de datos PostgreSQL, Redis y Kafka se orquestan mediante Docker Compose, lo que da un entorno reproducible sin la complejidad de un clúster de Kubernetes desde el inicio. En la nube, el mismo conjunto de servicios se despliega en ECS Fargate detrás de un Application Load Balancer, con Spring Cloud Gateway como punto único de entrada y enrutamiento (ver sección 7).

## 14. Orden sugerido de construcción

1. **Usuarios** — todo el sistema depende de la autenticación; debe construirse primero.
2. **Catálogo y búsqueda** — CRUD relativamente simple; además genera datos reales de eventos necesarios para probar Inventario.
3. **Inventario** — el más complejo; se beneficia de tener eventos reales ya creados para realizar pruebas de concurrencia significativas.