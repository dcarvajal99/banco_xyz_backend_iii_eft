# cuentas-service — Gestión de Cuentas

Microservicio dueño de las **cuentas** del Banco XYZ: apertura, cierre y mantenimiento, tarjetas y retiros en cajero,
y el lugar donde se **aplican los pagos** (débito y crédito en una transacción local). Toma las cuentas migradas del
legacy desde el esquema `migracion` que publica el batch.

## Ficha

| | |
|---|---|
| Puertos | `8100` HTTPS (negocio) · `9100` HTTP (operación: health, métricas, circuitos) |
| Nombre en Eureka | `CUENTAS-SERVICE` (una instancia por réplica) |
| Configuración | Config Server: `configuracion/cuentas-service.properties` + `application.properties` (+ `application-nube`) |
| Datos | Esquema `cuentas` (Flyway `V1__esquema_cuentas.sql`); lee `migracion.cierre_cuenta` |
| Llama a | `clientes-service` (titular de una cuenta nueva) con token `client_credentials` (scope `clientes.leer`) |
| Kafka | Consume `pagos.solicitados` · produce `cuentas.movimientos` y `seguridad.alertas` (outbox) |
| Paquete raíz | `com.bancoxyz.cuentas` |

## API (`/api/v1`)

| Método | Ruta | Scope | Para qué |
|---|---|---|---|
| GET | `/cuentas/{id}` | `cuentas.leer` | Una cuenta (un cliente solo ve las suyas) |
| GET | `/clientes/{id}/cuentas` | `cuentas.leer` | Cuentas de un cliente |
| GET | `/cuentas/{id}/movimientos` | `cuentas.leer` | Movimientos paginados (migrados + en línea) |
| GET | `/cuentas/{id}/estados-anuales` | `cuentas.leer` | Estados de cuenta anuales del batch |
| POST | `/cuentas` | `cuentas.administrar` | Apertura (saldo cero; ids desde 200001) |
| PATCH | `/cuentas/{id}` | `cuentas.administrar` | Mantenimiento: alias y estado `ACTIVA`/`BLOQUEADA` |
| POST | `/cuentas/{id}/cierre` | `cuentas.administrar` | Cierre (exige saldo cero; una cuenta cerrada no cambia más) |
| POST | `/autenticacion/tarjetas` | `cajero.operar` | Tarjeta + PIN (hash con pepper, bloqueo al tercer error) |
| POST | `/cuentas/{id}/retiros` | `cajero.operar` | Retiro con `Idempotency-Key`, límite diario por tarjeta |
| GET | `/reportes/calidad`, `/reportes/resumen-diario` | `reportes.leer` | Reportes de la migración para el ejecutivo |

Errores en `application/problem+json` (`codigo`, `correlacionId`, `errores[]` en validación). OpenAPI en
`/swagger-ui.html`.

## Decisiones

- **Seguridad en la puerta:** JWT de banco-auth (firma, emisor, audiencia, scope) más el certificado del llamante: un
  token de servicio debe venir con el certificado cuyo CN es su `sub`; un token de usuario, solo desde bff-web o
  bff-movil (`FiltroCertificadoDeLlamante`).
- **Resiliencia:** la llamada a clientes-service usa Bulkhead + CircuitBreaker + Retry (`clientes`). Si no responde,
  la apertura usa el titular que ya figura en otra cuenta del cliente; si no hay ninguna, 503 `CLIENTES_NO_DISPONIBLE`
  (no se abre una cuenta a nombre de nadie).
- **Consistencia:** el pago se aplica en una transacción con bloqueo de las filas (`PESSIMISTIC_WRITE`), `CHECK` de
  saldo ≥ 0, `evento_procesado` (consumidor idempotente) y `pago_aplicado` (una fila por pago). El evento de resultado
  sale por el outbox (`for update skip locked`: varias réplicas no publican dos veces).
- **Escalable:** sin puerto publicado ni nombre fijo; la sincronización inicial desde `migracion` usa
  `insert … on conflict do nothing`, así que varias réplicas arrancan a la vez sin duplicar.

## Ejecutar y probar

```bash
./mvnw verify                                   # pruebas (H2 modo PostgreSQL, Kafka embebido) + JaCoCo
docker compose up -d --build cuentas-service    # desde la raíz, dentro del ecosistema
```
