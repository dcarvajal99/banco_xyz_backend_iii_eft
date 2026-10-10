# bff-web — Backend for Frontend del navegador

BFF de la **banca en línea** (cliente) y de la **consola del ejecutivo**. Sirve la página (`static/`) y una API pensada
para pantallas grandes: el panel completo llega en una sola respuesta que junta cuatro llamadas a tres microservicios.

## Ficha

| | |
|---|---|
| Puertos | `8090` HTTPS (publicado en 127.0.0.1) · `9090` HTTP (operación) |
| Nombre en Eureka | `BFF-WEB` |
| Configuración | Config Server: `configuracion/bff-web.properties` (URL de los servicios por nombre y circuitos) |
| Llama a | `cuentas-service`, `clientes-service`, `pagos-service` por Eureka, con el token del usuario (token relay) |
| Autenticación | Cliente OAuth 2.0 **confidencial** `bff-web` de banco-auth (authorization_code + PKCE) |
| Paquete raíz | `com.bancoxyz.bff.web` |

## API (`/web/api`)

| Método | Ruta | Para qué |
|---|---|---|
| GET | `/sesion` | Quién tiene la sesión (público: sin sesión responde `autenticado=false`) |
| GET | `/panel` | Ficha, cuentas completas con totales, pagos recientes y avisos; `noDisponibles` si una parte falló |
| GET | `/cuentas/{id}/movimientos`, `/cuentas/{id}/estados-anuales` | Detalle de una cuenta |
| POST | `/pagos/{transferencias\|depositos\|servicios}` | Pago con `Idempotency-Key` (202) |
| GET | `/pagos/{id}` | Estado del pago |
| PUT | `/perfil` | Correo, teléfono y dirección del propio cliente |
| POST | `/salir` | Cierra la sesión (204) |
| GET/POST | `/ejecutivo/clientes` · GET `/ejecutivo/clientes/{id}` y `/{id}/cuentas` | Consola: clientes y alta |
| POST · PATCH · POST | `/ejecutivo/cuentas` · `/ejecutivo/cuentas/{id}` · `/ejecutivo/cuentas/{id}/cierre` | Apertura, mantenimiento y cierre |
| GET | `/ejecutivo/reportes/calidad`, `/ejecutivo/reportes/resumen-diario` | Reportes de la migración |

OpenAPI en `/swagger-ui.html`. Errores en ProblemDetail (`NO_AUTENTICADO`, `CSRF_INVALIDO`, `ACCESO_DENEGADO`,
`SERVICIO_NO_DISPONIBLE` o el del microservicio tal cual).

## Decisiones

- **Token handler:** el login lo hace el servidor; access, refresh e id token quedan en la sesión del BFF y el
  navegador solo recibe la cookie `BFF_WEB_SESION` (HttpOnly, Secure, SameSite). Un XSS no puede robar un token que la
  página nunca tuvo. Las escrituras exigen el token CSRF (`XSRF-TOKEN` → encabezado `X-XSRF-TOKEN`).
- **Respuesta completa para la web:** el panel trae los 17 campos de cada cuenta y los totales calculados; el móvil y el
  cajero reciben otra forma del mismo dato.
- **Degradación parcial:** un circuito por servicio (política `http-interno`). Si clientes-service o pagos-service no
  responden, el panel sale igual sin esa sección; si cuentas-service no responde, 503. Un 503 con `codigo` del
  servicio (p. ej. `CLIENTES_NO_DISPONIBLE`) llega tal cual y no abre el circuito de ese servicio.
- **Mismo hilo:** los circuitos corren sin pool de hilos ni TimeLimiter, para que la llamada vea la sesión, el token y
  el id de correlación de la solicitud.
- **Consola del ejecutivo solo en la web:** banco-auth no entrega tokens de la app móvil a un ejecutivo.

## Ejecutar y probar

```bash
./mvnw verify
infra/scripts/bff_canales.sh       # desde la raíz, con el ecosistema arriba; o https://localhost:8090 en el navegador
```
