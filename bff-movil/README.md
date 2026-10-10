# bff-movil — Backend for Frontend de la app móvil

BFF de la **app del cliente**: respuestas livianas con solo los datos que caben en la pantalla, comprimidas y
cacheables, para gastar menos datos móviles y responder rápido en redes lentas.

## Ficha

| | |
|---|---|
| Puertos | `8091` HTTPS (publicado en 127.0.0.1) · `9091` HTTP (operación) |
| Nombre en Eureka | `BFF-MOVIL` |
| Configuración | Config Server: `configuracion/bff-movil.properties` (servicios por nombre, circuito `core`) |
| Llama a | `cuentas-service` y `pagos-service` por Eureka, reenviando el token de la app (token relay) |
| Autenticación | Servidor de recursos: JWT de banco-auth emitido al cliente **público** `banca-movil` (PKCE) |
| Paquete raíz | `com.bancoxyz.bff.movil` |

## API (`/movil/v1`)

| Método | Ruta | Scope | Para qué |
|---|---|---|---|
| GET | `/inicio` | `cuentas.leer` | Nombre, disponible, deuda, cuentas `{id, tipo, saldo}` y últimos movimientos |
| GET | `/cuentas/{id}/movimientos` | `cuentas.leer` | Movimientos en páginas cortas |
| POST | `/transferencias` | `pagos.escribir` | Transferencia con `Idempotency-Key` (202) |
| GET | `/pagos/{id}` | `pagos.leer` | Estado del pago |

## Decisiones

- **PKCE sin secreto:** una app instalada no puede guardar un secreto de cliente (se extrae del binario); por eso es
  un cliente público con PKCE (RFC 8252) y redirección loopback. Access token corto y refresh rotativo.
- **Validación propia del canal:** firma (JWKS de banco-auth), emisor, audiencia `banco-xyz` y **`azp = banca-movil`**:
  un token válido de otro cliente OAuth (bff-cajero, bff-web) no entra.
- **Respuesta liviana:** 3 campos por cuenta en vez de 17; `gzip` (`server.compression`); `ETag` con `304 Not
  Modified`; las cuentas se guardan 10 s en caché (`banco.movil.cache-cuentas-segundos`).
- **Falla rápido:** timeout de lectura de 3 s y TimeLimiter de 4 s en el circuito `core`; el token se captura en el
  hilo de la solicitud antes de entrar al circuito.

## Ejecutar y probar

```bash
./mvnw verify
infra/scripts/bff_canales.sh       # la parte MOVIL actúa como la app (PKCE contra banco-auth)
```
