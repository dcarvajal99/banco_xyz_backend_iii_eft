# bff-movil — Banco XYZ

Microservicio que atiende la app móvil del banco: una portada con lo esencial (disponible, deuda, cuentas y los
últimos movimientos) y la paginación de movimientos por cursor para "cargar más". La identidad viaja en un access
token JWT corto, sin sesión de servidor, y las respuestas se optimizan para una red móvil (livianas, comprimidas y
cacheables). Hacia el backend habla mTLS y Basic Auth con el core, al que encuentra por nombre en Eureka.

## Ficha

| | |
|---|---|
| Puerto HTTPS | `8082` |
| Puerto de operación (127.0.0.1) | `9082` (health, métricas, circuit breakers) |
| Nombre en Eureka | `BFF-MOVIL` |
| Configuración central | `banco-xyz-cloud/configuracion/bff-movil.properties` + `application.properties` (comunes a todos) |
| Depende de | `banco-core-api` (mTLS + Basic, por descubrimiento), Config Server `:8888`, Eureka `:8761` |
| Paquete raíz | `com.bancoxyz.bff.movil` |

## API

| Método | Ruta | Para qué |
|---|---|---|
| POST | `/movil/v1/sesiones` | Inicia sesión desde un dispositivo (usuario, clave, `dispositivoId`); no acepta ejecutivos |
| POST | `/movil/v1/sesiones/renovar` | Renueva el par de tokens; el refresh usado queda consumido |
| DELETE | `/movil/v1/sesiones` | Cierra sesión: revoca la familia de tokens |
| GET | `/movil/v1/inicio` | Portada: disponible, deuda, cuentas y los 5 últimos movimientos de todas las cuentas |
| GET | `/movil/v1/cuentas/{cuentaId}/movimientos` | Movimientos paginados por cursor (`cursor`, `limite`, máximo 20) |

## Seguridad

Sin sesión de servidor (`SessionCreationPolicy.STATELESS`): la identidad es un access token JWT (HS256, 5 min,
emisor y audiencia propios del canal) más un refresh token rotativo (7 días) ligado al `dispositivoId`; renovar
consume el refresh anterior y `FiltroDispositivo` revisa que el dispositivo del token coincida con el de la
solicitud, para detectar un token copiado a otro teléfono. El validador de JWT tiene tolerancia cero al
vencimiento (el core y el BFF corren en el mismo proceso, sin relojes que compensar).

Autorización: solo clientes (el core rechaza a los ejecutivos con `403 ROL_NO_PERMITIDO_EN_CANAL`), y cada uno ve
solo sus propias cuentas y movimientos (el core resuelve con el `usuarioId` del token; una cuenta ajena responde
404).

Hacia el core: mTLS con el certificado `bff-movil` (servidor y cliente) más `Basic` con el usuario de servicio
`bff-movil` y su clave (`BANCO_CORE_CLAVE`).

## Cómo encuentra al core

Con `banco.core.descubrimiento=true` (lo entrega el Config Server), `config/DescubrimientoDelCore.java` registra un
`RestClient.Builder` `@LoadBalanced`: la URL del core es el nombre del servicio, `https://banco-core-api/api/v1`, y
Spring Cloud LoadBalancer la resuelve contra una instancia registrada en Eureka en cada llamada, conservando el
certificado de cliente (mTLS). Sin Config Server, el jar arranca con `banco.core.descubrimiento=false` y la URL fija
`https://localhost:8080/api/v1` (`application.properties`).

## Tolerancia a fallos

Circuito Resilience4j `core`: ventana de 6 llamadas, mínimo 3, umbral de falla 50 %, 10 s en `OPEN`, 2 llamadas
permitidas en `HALF_OPEN`. `ErrorDeNegocioDelCore` (4xx y `CUENTA_OCUPADA`) no cuenta como falla; cualquier otro
5xx sí. El límite de tiempo del circuito (4 s) queda por encima del `read-timeout` del cliente HTTP (3 s, más corto
que en los otros canales: en una red móvil es mejor fallar rápido que dejar la app congelada). La política la
entrega el Config Server; `application.properties` trae una copia de respaldo idéntica para arrancar protegido si
el Config Server no responde.

Fallback: cualquier error hacia el core (circuito abierto, sin instancias en Eureka, timeout o 5xx no traducible)
responde `503 SERVICIO_NO_DISPONIBLE`.

## Estructura

```
com.bancoxyz.bff.movil
├── BffMovilApplication.java
├── adaptador/     AdaptadorMovil, CuentaMovil, MovimientoMovil (DTO livianos hacia la app)
├── config/        ConfiguracionGeneral (portada en paralelo), DescubrimientoDelCore, FiltroCorrelacion, Propiedades*
├── core/          CoreApi + ClienteCoreHttp y los DTO de lectura del core
├── error/         ErrorMovil, ManejadorDeErroresMovil, RespuestaErrorMovil
├── inicio/        ControladorDeInicio, InicioMovil
├── movimientos/   ControladorDeMovimientos, Cursor, PaginaMovil
├── proxy/         ProxyCoreConCache (misma interfaz CoreApi, cachea cuentas y páginas 10 s)
└── seguridad/     ConfiguracionSeguridad, ControladorDeSesiones, EmisorDeTokens, AlmacenDeRefresh, FiltroDispositivo
```

## Construir y probar

```bash
./mvnw verify
```

Informe de cobertura JaCoCo en `target/site/jacoco/index.html`.

## Ejecutar solo

```bash
BANCO_CERTIFICADOS=../banco-xyz-cloud/certificados java -jar target/bff-movil-1.0.0.jar
```

Con Config Server y Eureka disponibles, se puede fijar además `BANCO_CONFIG_SERVER` si no corren en
`http://127.0.0.1:8888`. Para levantar el ecosistema completo (Config Server, Eureka, core y los tres BFF) usar
`banco-xyz-cloud/scripts/levantar_servicios.sh`.

## Pruebas destacadas

- `CircuitoDelCoreTest` — el circuito cierra en operación normal, abre y corta el paso tras fallas del servidor, y
  comprueba que solo las fallas del core (no los errores de negocio) cuentan.
- `DescubrimientoDelCoreTest` — el core se encuentra por nombre vía Spring Cloud LoadBalancer contra un
  `DiscoveryClient` simple, manteniendo el mTLS.
- `TlsDelBffMovilTest` — el BFF con el perfil `tls` real: la portada viaja por HTTP/2 sobre TLS 1.3, comprimida, con
  ETag y revalidación (304) tal como la vería la app.
- `ContratoConsumidorTest` — los records del BFF móvil se construyen desde `contratos/core-api` sin campos
  faltantes.
- `MovilIntegracionTest` — portada, ETag y proxy de caché, paginación por cursor y seguridad del token, de punta a
  punta.
- `ProxyCoreConCacheTest` — cuentas y páginas de movimientos en caché con vencimiento y desalojo de las vencidas.
- `TokensYAlmacenTest` — reclamos del access token, rechazo de un secreto corto y comportamiento de un refresh
  vencido.

## Enlaces

[README general](../README.md) · [infraestructura Spring Cloud](../banco-xyz-cloud/README.md)
