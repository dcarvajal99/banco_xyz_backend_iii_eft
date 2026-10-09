# bff-web — Banco XYZ

Microservicio que atiende el navegador del banco: la banca por internet del cliente y la consola del ejecutivo.
Expone un panel agregado, el detalle de cuentas con movimientos y estados anuales, y los reportes del ejecutivo,
todo autenticado con sesión de servidor y protegido con CSRF. Hacia el backend habla mTLS y Basic Auth con el core,
al que encuentra por nombre en Eureka.

## Ficha

| | |
|---|---|
| Puerto HTTPS | `8081` |
| Puerto de operación (127.0.0.1) | `9081` (health, métricas, circuit breakers) |
| Nombre en Eureka | `BFF-WEB` |
| Configuración central | `banco-xyz-cloud/configuracion/bff-web.properties` + `application.properties` (comunes a todos) |
| Depende de | `banco-core-api` (mTLS + Basic, por descubrimiento), Config Server `:8888`, Eureka `:8761` |
| Paquete raíz | `com.bancoxyz.bff.web` |

## API

| Método | Ruta | Para qué |
|---|---|---|
| POST | `/web/api/sesion` | Inicia sesión (solo `application/json`); responde la cookie `BFFWEB_SESION` |
| GET | `/web/api/sesion` | Usuario de la sesión actual |
| DELETE | `/web/api/sesion` | Cierra sesión (exige token CSRF) |
| GET | `/web/api/csrf` | Entrega el token CSRF y fuerza la cookie `XSRF-TOKEN` |
| GET | `/web/api/panel` | Panel de inicio: el del cliente o el del ejecutivo según quién inició sesión |
| GET | `/web/api/cuentas/{cuentaId}` | Cuenta completa con estados anuales y la primera página de movimientos |
| GET | `/web/api/cuentas/{cuentaId}/movimientos` | Movimientos paginados (`pagina`, `tamano`) con metadatos para una tabla |
| GET | `/web/api/ejecutivo/resumen-diario` | Resumen diario de transacciones con tabla y serie para gráfico (`desde`, `hasta`) |
| GET | `/web/api/ejecutivo/calidad` | Calidad de los cierres publicados y filas rechazadas por el batch |
| GET | `/web/api/ejecutivo/clientes/{clienteId}/panel` | Panel de un cliente visto por el ejecutivo |

## Seguridad

El navegador nunca ve un token: la identidad queda en el servidor y solo se entrega una cookie `BFFWEB_SESION`
`HttpOnly; Secure; SameSite=Strict` (sesión de 15 min). El login (`POST /web/api/sesion`) regenera el id de sesión
para evitar fijación de sesión. Todo lo demás exige CSRF: la cookie `XSRF-TOKEN` (sin `HttpOnly`) se reenvía en el
encabezado `X-XSRF-TOKEN`, y el login queda exento solo porque acepta únicamente JSON.

Autorización por rol: `CLIENTE` ve solo lo suyo (el core decide con el `usuarioId` de la sesión; una cuenta ajena
responde 404); `/web/api/ejecutivo/**` exige `ROLE_EJECUTIVO`. Todo lo que no está listado en la cadena de seguridad
se rechaza (`anyRequest().denyAll()`).

Hacia el core: mTLS con el certificado `bff-web` (el mismo para servidor y cliente) más `Basic` con el usuario de
servicio `bff-web` y su clave (`BANCO_CORE_CLAVE`), añadido por `BasicAuthenticationInterceptor` en `ClienteCoreWeb`.

## Cómo encuentra al core

Con `banco.core.descubrimiento=true` (lo entrega el Config Server), `config/DescubrimientoDelCore.java` registra un
`RestClient.Builder` `@LoadBalanced`: la URL del core es el nombre del servicio, `https://banco-core-api/api/v1`, y
Spring Cloud LoadBalancer la resuelve contra una instancia registrada en Eureka en cada llamada, conservando el
certificado de cliente (mTLS). Sin Config Server, el jar arranca con `banco.core.descubrimiento=false` y la URL fija
`https://localhost:8080/api/v1` (`application.properties`).

## Tolerancia a fallos

Circuito Resilience4j `core`: ventana de 6 llamadas, mínimo 3, umbral de falla 50 %, 10 s en `OPEN`, 2 llamadas
permitidas en `HALF_OPEN`. `ErrorDeNegocioDelCore` (4xx y `CUENTA_OCUPADA`) no cuenta como falla; cualquier otro
5xx sí. El límite de tiempo del circuito (6 s) queda por encima del `read-timeout` del cliente HTTP (5 s): quien
corta la llamada es el cliente. La política la entrega el Config Server; `application.properties` trae una copia de
respaldo idéntica para arrancar protegido si el Config Server no responde.

Fallback: cualquier error hacia el core (circuito abierto, sin instancias en Eureka, timeout o 5xx no traducible)
responde `503 SERVICIO_NO_DISPONIBLE`.

## Estructura

```
com.bancoxyz.bff.web
├── BffWebApplication.java
├── adaptador/    DTO de salida hacia el navegador (AdaptadorWeb, PaginaWeb, ResumenDiarioWeb, CalidadWeb, ...)
├── config/       ConfiguracionGeneral (agregador), DescubrimientoDelCore, FiltroCorrelacion, PropiedadesCore/Web
├── core/         ClienteCoreWeb y los DTO de lectura del core (CuentaCore, MovimientoCore, ErrorDelCore, ...)
├── cuenta/       ControladorDeCuentas, DetalleCuentaWeb
├── ejecutivo/    ControladorEjecutivo
├── error/        ErrorWeb, ManejadorDeErroresWeb, ProblemasWeb
├── panel/        ControladorDePanel + Strategy (PanelDeCliente, PanelDeEjecutivo) + AgregadorDeCliente (paralelo)
└── seguridad/    ConfiguracionSeguridad, ControladorDeSesion, UsuarioWeb
```

## Construir y probar

```bash
./mvnw verify
```

Informe de cobertura JaCoCo en `target/site/jacoco/index.html`.

## Ejecutar solo

```bash
BANCO_CERTIFICADOS=../banco-xyz-cloud/certificados java -jar target/bff-web-1.0.0.jar
```

Con Config Server y Eureka disponibles, se puede fijar además `BANCO_CONFIG_SERVER` si no corren en
`http://127.0.0.1:8888`. Para levantar el ecosistema completo (Config Server, Eureka, core y los tres BFF) usar
`banco-xyz-cloud/scripts/levantar_servicios.sh`.

## Pruebas destacadas

- `CircuitoDelCoreTest` — el circuito cierra en operación normal, abre y corta el paso tras fallas del servidor, y
  comprueba que solo las fallas del core (no los errores de negocio) cuentan.
- `DescubrimientoDelCoreTest` — el core se encuentra por nombre vía Spring Cloud LoadBalancer contra un
  `DiscoveryClient` simple, manteniendo el mTLS.
- `TlsDelBffWebTest` — el BFF con el perfil `tls` real: login por HTTPS y panel comprimido.
- `ContratoConsumidorTest` — los DTO que la web lee del core se construyen desde `contratos/core-api` sin campos
  faltantes.
- `CuentasYEjecutivoTest` — detalle de cuenta, paginación, cuenta ajena con el core caído y la consola del
  ejecutivo.
- `IdentidadEnAgregadorTest` — con un solo hilo en el agregador, la identidad de cada solicitud no se mezcla entre
  usuarios concurrentes.
- `SeguridadWebTest` — acceso sin sesión, login y sesión, login restringido a JSON, y fijación de sesión.

## Enlaces

[README general](../README.md) · [infraestructura Spring Cloud](../banco-xyz-cloud/README.md)
