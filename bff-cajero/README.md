# bff-cajero — Backend for Frontend de cajeros automáticos

BFF de la **red de cajeros**: consulta de saldo y retiro de efectivo con el nivel de seguridad de una operación
crítica. Responde con mensajes listos para la pantalla del cajero y la acción que debe seguir el terminal.

## Ficha

| | |
|---|---|
| Puertos | `8083` HTTPS con certificado de cliente (publicado en 127.0.0.1) · `9083` HTTP (operación) |
| Nombre en Eureka | `BFF-CAJERO` |
| Configuración | Config Server: `configuracion/bff-cajero.properties` (cuentas-service por nombre, circuito `core`) |
| Llama a | `cuentas-service` con su token `client_credentials` (scope `cajero.operar`) y su certificado |
| Terminales | `ATM-001` y `ATM-002`: certificado de la CA del banco + secreto HMAC propio |
| Paquete raíz | `com.bancoxyz.bff.cajero` |

## API (`/cajero/v1`)

| Método | Ruta | Credencial | Para qué |
|---|---|---|---|
| POST | `/sesiones` | Terminal (mTLS + firma) | `{numeroTarjeta, pin}` → token de sesión corto, titular y acción `MOSTRAR_MENU` |
| GET | `/saldo` | Terminal + token de sesión | Saldo con `mensajePantalla` |
| POST | `/retiros` | Terminal + token de sesión + `Idempotency-Key` | `{monto}` → comprobante y saldo restante |

Firma de cada solicitud (`X-Terminal-Id`, `X-Marca-Tiempo`, `X-Nonce`, `X-Firma`):

```
canonica = METODO \n RUTA \n MARCA_TIEMPO \n NONCE \n IDEMPOTENCY_KEY \n sha256hex(cuerpo)
X-Firma  = Base64(HMAC-SHA256(secreto del terminal, canonica))
```

Los errores incluyen `mensajePantalla`, `accion` (`SOLICITAR_PIN`, `DEVOLVER_TARJETA`, `MOSTRAR_MENU`…) y, con PIN
incorrecto, `intentosRestantes`.

## Decisiones

- **Dos factores del equipo:** el certificado identifica al terminal (CN = id) y la firma HMAC prueba que la solicitud
  no fue alterada ni repetida (marca de tiempo ± ventana y nonce de un solo uso). Sin certificado: 401
  `CERTIFICADO_TERMINAL_REQUERIDO` y el cajero devuelve la tarjeta.
- **Dos factores del cliente:** tarjeta (se busca por HMAC del número, nunca en claro) y PIN; al tercer error la
  tarjeta se bloquea y se publica `TarjetaBloqueada`.
- **Retiro idempotente:** el reintento del terminal con la misma `Idempotency-Key` devuelve el mismo comprobante sin
  descontar dos veces (clave única por canal en cuentas-service).
- **Credencial propia hacia el backend:** el cliente del cajero no tiene cuenta web; el BFF opera con su token
  `client_credentials` y cuentas-service exige que ese token llegue con el certificado de bff-cajero.

## Ejecutar y probar

```bash
./mvnw verify
infra/scripts/bff_canales.sh       # la parte CAJERO actúa como el terminal ATM-001 (infra/scripts/cajero_lib.sh)
```
