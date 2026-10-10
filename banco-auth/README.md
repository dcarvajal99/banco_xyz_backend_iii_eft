# banco-auth — Servidor de autorización OAuth 2.0 / OpenID Connect

Emite los tokens con que los canales y los servicios del Banco XYZ se identifican entre sí (Spring Authorization
Server). Es el único lugar donde una persona escribe su clave; la clave se verifica en clientes-service.

## Ficha

| | |
|---|---|
| Puertos | `8081` HTTPS (publicado en 127.0.0.1: el navegador y la app llegan al login) · `9081` HTTP (operación) |
| Nombre en Eureka | `BANCO-AUTH` |
| Configuración | Config Server: `configuracion/banco-auth.properties` (GitHub, vínculos, circuito `clientes`) |
| Llama a | `clientes-service` (usuario y clave) con Basic + certificado, por Eureka |
| Endpoints | `/oauth2/authorize`, `/oauth2/token`, `/oauth2/jwks`, `/userinfo`, `/.well-known/openid-configuration`, `/login` |
| Paquete raíz | `com.bancoxyz.auth` |

## Clientes OAuth registrados

| Cliente | Tipo | Flujo | Scopes | Quién lo usa |
|---|---|---|---|---|
| `bff-web` | Confidencial | authorization_code + PKCE, refresh | openid, profile, cuentas.*, clientes.*, pagos.*, reportes.leer | bff-web (token handler) |
| `banca-movil` | **Público** (sin secreto) | authorization_code + PKCE, refresh | openid, cuentas.leer, clientes.leer, pagos.escribir, pagos.leer | App móvil (solo clientes) |
| `bff-cajero` | Confidencial | client_credentials | cajero.operar | bff-cajero hacia cuentas-service |
| `pagos-service` | Confidencial | client_credentials | cuentas.leer | Prevalidación de la cuenta de origen |
| `cuentas-service` | Confidencial | client_credentials | clientes.leer | Titular de una cuenta nueva |
| `operacion-banco` | Confidencial | client_credentials | claves.administrar | Rotar y retirar claves de firma (`/actuator/claves`) |

Los secretos llegan por variables de entorno (`BANCO_OAUTH_*`) y se guardan con BCrypt.

## Decisiones

- **Un servidor, un mecanismo por canal:** el canal decide el tipo de cliente (confidencial en el servidor web, público
  con PKCE en la app, credenciales propias en el cajero y entre servicios).
- **Mínimo privilegio por rol:** el token de un cliente no lleva scopes de administración y el de un ejecutivo no lleva
  `pagos.escribir`. Un ejecutivo no obtiene token de `banca-movil` (`access_denied`): la consola existe solo en la web.
- **Claims del banco:** `rol`, `usuario_id`, `cliente_id`, `origen` y `azp` (el cliente OAuth que pidió el token), que
  los servicios usan para autorizar sin consultar a nadie.
- **JWT RS256 con rotación:** el `kid` de cada token identifica su clave en el JWK Set; una clave retirada sigue
  publicada hasta que vencen los tokens que firmó (access token de 5 minutos).
- **Identidad federada opcional:** "Ingresar con GitHub" (banco-auth es cliente OAuth de GitHub); solo entran cuentas
  de GitHub vinculadas a un usuario del banco. El client-secret de GitHub nunca pasa por el Config Server.
- **Resiliencia:** la verificación en clientes-service usa CircuitBreaker + Retry + Bulkhead (`clientes`); si no
  responde, el login muestra que el servicio no está disponible (las sesiones abiertas siguen funcionando).

## Ejecutar y probar

```bash
./mvnw verify
infra/scripts/bff_canales.sh       # login web y PKCE móvil reales contra banco-auth
```
