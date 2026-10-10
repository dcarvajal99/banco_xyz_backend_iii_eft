# pagos-service — Procesamiento de Pagos

Microservicio dueño de los **pagos** del Banco XYZ: transferencias, depósitos y pagos de servicios. Responde
`202 Accepted` al instante y deja que cuentas-service mueva los saldos; el resultado vuelve por Kafka (saga
simplificada). Aplica las reglas antifraude antes de pedir el movimiento.

## Ficha

| | |
|---|---|
| Puertos | `8102` HTTPS (negocio) · `9102` HTTP (operación) |
| Nombre en Eureka | `PAGOS-SERVICE` |
| Configuración | Config Server: `configuracion/pagos-service.properties` (incluye las reglas de fraude) |
| Datos | Esquema `pagos` (pago, historial, outbox, evento_procesado) |
| Llama a | `cuentas-service` (prevalidación de la cuenta de origen) con token `client_credentials` (`cuentas.leer`) |
| Kafka | Produce `pagos.solicitados` y `seguridad.alertas` · consume `cuentas.movimientos` |
| Paquete raíz | `com.bancoxyz.pagos` |

## API (`/api/v1/pagos`)

| Método | Ruta | Scope | Para qué |
|---|---|---|---|
| POST | `/transferencias` | `pagos.escribir` | `{cuentaOrigen, cuentaDestino, monto}` + `Idempotency-Key` → 202 |
| POST | `/depositos` | `pagos.escribir` | `{cuentaDestino, monto}` → 202 |
| POST | `/servicios` | `pagos.escribir` | `{cuentaOrigen, convenio, monto}` → 202 |
| GET | `/{id}` | `pagos.leer` | Estado (`SOLICITADO`, `COMPLETADO`, `RECHAZADO`) con su historial |
| GET | `/` | `pagos.leer` | Pagos recientes del cliente del token |

La misma `Idempotency-Key` devuelve el mismo pago (200 + `Idempotency-Replayed: true`); con otro cuerpo, 409.

## Decisiones

- **Asíncrono con outbox:** el pago `SOLICITADO` y su evento `PagoSolicitado` se guardan en la misma transacción; un
  publicador lo envía a Kafka con Retry + CircuitBreaker (`publicacion-kafka`). Si Kafka cae, nada se pierde.
- **Comportamiento alternativo:** si cuentas-service no responde a la prevalidación, el pago se acepta igual con
  validación `DIFERIDA` y cuentas-service decide al consumir el evento (el cliente no queda bloqueado).
- **Antifraude configurable:** `banco.pagos.fraude.monto-maximo` y `cuentas-en-observacion` vienen del Config Server;
  un rechazo publica `OperacionSospechosa` en `seguridad.alertas` (clientes-service avisa al cliente).
- **Consumidor idempotente:** `cuentas.movimientos` se procesa una vez por `eventoId`; un estado final no vuelve atrás.

## Ejecutar y probar

```bash
./mvnw verify
infra/scripts/saga_pagos.sh        # desde la raíz, con el ecosistema arriba
```
