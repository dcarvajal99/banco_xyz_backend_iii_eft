# Banco XYZ — Migración del legacy a microservicios (EFT Desarrollo Backend III)

**Repositorio:** <https://github.com/dcarvajal99/banco_xyz_backend_iii_eft>

Evaluación Final Transversal de **Desarrollo Backend III (PBY2203)**, Duoc UC · Diego Carvajal · octubre 2026.

El Banco XYZ reemplaza su plataforma legacy (COBOL y scripts Shell sobre mainframe) por **diez aplicaciones
independientes** con Spring Boot y Spring Cloud: un proceso batch que migra los datos del legacy, tres
*Backend for Frontend* (web, móvil y cajero automático), tres microservicios de negocio (cuentas, pagos y clientes), un
servidor de autorización OAuth 2.0 y el plano de control (Config Server y Eureka). Todo corre en contenedores con un
solo `docker compose up`.

| Documento | Para qué |
|---|---|
| [`instrucciones.md`](instrucciones.md) | Ejecutar y probar cada componente paso a paso |
| [`despliegue.md`](despliegue.md) | Desplegar el sistema en AWS |
| `PBY2203_EFT_S9_Informe.pdf` (en la entrega) | Informe técnico con arquitectura, decisiones y evidencias |
| `README.md` de cada carpeta | Ficha técnica de cada aplicación |

## Las diez aplicaciones

Cada carpeta es un proyecto Maven completo (su `pom.xml` sin padre común, sus pruebas y su `Dockerfile`). Ninguna
depende del código de otra: se encuentran por el Config Server, por Eureka y por tópicos de Kafka.

| # | Aplicación | Puerto | Rol |
|---|---|---|---|
| 1 | [`bff-web`](bff-web) | 8090 | BFF del navegador: login OAuth2 hecho por el servidor (token handler), panel completo, consola del ejecutivo |
| 2 | [`bff-movil`](bff-movil) | 8091 | BFF de la app: valida el JWT del cliente público `banca-movil`, respuestas livianas con gzip y ETag |
| 3 | [`bff-cajero`](bff-cajero) | 8083 | BFF de cajeros automáticos: certificado del terminal + firma HMAC + tarjeta y PIN; retiros idempotentes |
| 4 | [`clientes-service`](clientes-service) | 8101 (interno) | Gestión de clientes: datos personales, perfiles, credenciales y avisos |
| 5 | [`cuentas-service`](cuentas-service) | 8100 (interno) | Gestión de cuentas: apertura, cierre, mantenimiento, tarjetas, retiros; aplica los pagos |
| 6 | [`pagos-service`](pagos-service) | 8102 (interno) | Procesamiento de pagos: transferencias, depósitos, pagos de servicios y reglas antifraude |
| 7 | [`config-server`](config-server) | 8888 | Spring Cloud Config (HTTPS + mTLS + Basic) con la configuración de todos y el perfil `nube` |
| 8 | [`eureka-server`](eureka-server) | 8761 / 8762 | Descubrimiento de servicios: dos nodos que se replican |
| 9 | [`banco-auth`](banco-auth) | 8081 | Servidor de autorización OAuth 2.0 / OpenID Connect (Spring Authorization Server) |
| 10 | [`banco-xyz-batch`](banco-xyz-batch) | — | Spring Batch: transacciones diarias, intereses mensuales y estados de cuenta anuales |

Además: [`infra/`](infra) (PKI de desarrollo, scripts de evidencia) y [`docker-compose.yaml`](docker-compose.yaml).

## Inicio rápido

Requisitos: Docker con Compose v2 (8 GB de memoria para la VM), Java 17+ y Maven solo si se quieren correr las pruebas.

```bash
cp .env.ejemplo .env                      # opcional: sin .env se usan los valores de desarrollo
docker compose up -d --build --wait       # construye las 10 aplicaciones y las levanta en orden por salud
docker compose ps                         # 9 servicios healthy y el batch "Exited (0)"
```

Abrir <https://localhost:8090> (la CA de desarrollo está en `infra/certificados/ca/ca.crt` tras correr cualquier script
de `infra/scripts`). Usuarios: `diana.prince`, `john.doe`, `alice.brown`… con clave `Cliente2026!`, y `ejecutivo` con
`Ejecutivo2026!`. El detalle está en [`instrucciones.md`](instrucciones.md).

## Arquitectura

```
 Navegador          App móvil (PKCE)         Cajero (mTLS + HMAC + PIN)
     │ cookie HttpOnly    │ Bearer JWT              │
     ▼                    ▼                         ▼
  bff-web :8090      bff-movil :8091          bff-cajero :8083        banco-auth :8081 (OAuth2 / OIDC)
     └──────── token relay / token de servicio + certificado del BFF ────────┘
                 │  Eureka + Spring Cloud LoadBalancer · Resilience4j
     ┌───────────┼──────────────────┬──────────────────┐
 clientes-service        cuentas-service        pagos-service        (escalables con --scale)
     │   ▲                    │   ▲                  │   ▲
     └───┴──── Kafka: pagos.solicitados · cuentas.movimientos · seguridad.alertas ────┘
                 │
 PostgreSQL: esquemas clientes · cuentas · pagos · migracion ◄── banco-xyz-batch (Spring Batch)
 Plano de control: config-server :8888 · eureka-1 :8761 ⇄ eureka-2 :8762 · pki (CA de desarrollo)
```

- **Batch:** los tres procesos legacy en Spring Batch, con los tres archivos en paralelo y cada uno particionado;
  omisión y reintento por fila, conciliación de totales, decisor de calidad, reejecución automática y reanudación
  idempotente. Resultado equivalente al legacy (comparador independiente en Python: 0 diferencias).
- **BFF:** una API por canal, con su propia forma de autenticar y de responder (completa, liviana o de pantalla).
- **Microservicios:** cada uno dueño de su esquema; JWT de banco-auth + certificado del llamante en cada puerta;
  Resilience4j (CircuitBreaker, Retry, Bulkhead) con comportamiento alternativo; pagos asíncronos por Kafka con outbox
  transaccional y consumidores idempotentes.
- **Contenedores:** imagen por aplicación (multi-stage, JRE 21, usuario sin privilegios, healthcheck); los
  microservicios no publican puertos y escalan horizontalmente.

## Pruebas

```bash
infra/scripts/verificar_proyectos.sh      # mvn verify en cada una de las diez aplicaciones (pruebas + JaCoCo)
infra/scripts/probar_todo.sh              # las evidencias contra el ecosistema levantado
```

## Stack

Java 17 · Spring Boot 3.5.16 · Spring Cloud 2025.0.3 (Config, Netflix Eureka, LoadBalancer, CircuitBreaker) ·
Spring Security 6.5 + Spring Authorization Server · Spring Batch 5 · Resilience4j · Spring for Apache Kafka (Kafka
3.9.1 KRaft) · PostgreSQL 17 · Flyway · Docker Compose.
