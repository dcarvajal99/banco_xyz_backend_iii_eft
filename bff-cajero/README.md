# bff-cajero — Banco XYZ

Microservicio que atiende los cajeros automáticos (ATM): inicio de sesión con tarjeta y PIN, consulta de saldo y
retiro de efectivo. Es el canal con más controles de seguridad porque autentica dos partes distintas: el equipo
(el terminal, con mTLS y firma HMAC por solicitud) y el tarjetahabiente (tarjeta y PIN, con un token de sesión
corto). El fallback nunca autoriza un retiro sin el core. Hacia el backend habla mTLS y Basic Auth con el core, al
que encuentra por nombre en Eureka.

## Ficha

| | |
|---|---|
| Puerto HTTPS | `8083` |
| Puerto de operación (127.0.0.1) | `9083` (health, métricas, circuit breakers) |
| Nombre en Eureka | `BFF-CAJERO` |
| Configuración central | `banco-xyz-cloud/configuracion/bff-cajero.properties` + `application.properties` (comunes a todos) |
| Depende de | `banco-core-api` (mTLS + Basic, por descubrimiento), Config Server `:8888`, Eureka `:8761` |
| Paquete raíz | `com.bancoxyz.bff.cajero` |

## API

| Método | Ruta | Para qué |
|---|---|---|
| POST | `/cajero/v1/sesiones` | Inicia una sesión con tarjeta y PIN (solicitud firmada por el terminal) |
| DELETE | `/cajero/v1/sesiones` | Termina la sesión (el cliente retira la tarjeta) |
| GET | `/cajero/v1/saldo` | Consulta de saldo de la cuenta de la tarjeta |
| POST | `/cajero/v1/retiros` | Retiro de efectivo, idempotente por `Idempotency-Key` |

## Seguridad

Doble autenticación, en cadena: primero el **equipo**, después el **tarjetahabiente**.

- **Terminal.** `FiltroCertificadoDeTerminal` exige el certificado de cliente TLS del terminal (`server.ssl.client-auth=want`,
  CN = id del terminal) y que coincida con el encabezado `X-Terminal-Id`; `FiltroFirmaDeTerminal` exige además una
  firma HMAC de la solicitud con marca de tiempo dentro de ±30 s y nonce único (rechaza repeticiones). El
  certificado prueba qué equipo abre la conexión; la firma, que el mensaje no se alteró ni se repitió.
- **Tarjetahabiente.** `POST /cajero/v1/sesiones` valida el formato de la tarjeta (16 dígitos, dígito verificador
  Luhn) y el PIN antes de preguntarle al core, y emite un token de sesión JWT (HS256, emisor y audiencia propios,
  120 s, tolerancia cero al vencimiento). `FiltroSesionDeTerminal` verifica que esa sesión pertenece al mismo
  terminal que la abrió.

Autorización: solo la cuenta ligada a la tarjeta de la sesión (no hay navegación a otras cuentas).

Hacia el core: mTLS con el certificado `bff-cajero` (servidor y cliente) más `Basic` con el usuario de servicio
`bff-cajero` y su clave (`BANCO_CORE_CLAVE`).

## Cómo encuentra al core

Con `banco.core.descubrimiento=true` (lo entrega el Config Server), `config/DescubrimientoDelCore.java` registra un
`RestClient.Builder` `@LoadBalanced`: la URL del core es el nombre del servicio, `https://banco-core-api/api/v1`, y
Spring Cloud LoadBalancer la resuelve contra una instancia registrada en Eureka en cada llamada, conservando el
certificado de cliente (mTLS). Sin Config Server, el jar arranca con `banco.core.descubrimiento=false` y la URL fija
`https://localhost:8080/api/v1` (`application.properties`).

## Tolerancia a fallos

Circuito Resilience4j `core`: ventana de 6 llamadas, mínimo 3, umbral de falla 50 %, 10 s en `OPEN`, 2 llamadas
permitidas en `HALF_OPEN`. `ErrorDeNegocioDelCore` (4xx y `CUENTA_OCUPADA`) no cuenta como falla; cualquier otro
5xx sí. El límite de tiempo del circuito (6 s) queda por encima del `read-timeout` del cliente HTTP (5 s). La
política la entrega el Config Server; `application.properties` trae una copia de respaldo idéntica para arrancar
protegido si el Config Server no responde.

El fallback distingue si el retiro alcanzó a salir (`error/TraductorDeErroresDelCore.java`): si la solicitud **no
salió** (sin conexión, circuito abierto → `CircuitoDelCoreAbierto`, o sin instancias del core en Eureka →
`CoreNoRegistrado`), responde `503 SERVICIO_NO_DISPONIBLE` con la acción `DEVOLVER_TARJETA` (no pudo haber
descuento); si el retiro **salió y no hubo respuesta** (timeout u otro 5xx después de enviarlo), responde `504
RESULTADO_INCIERTO` con `REINTENTAR_MISMA_CLAVE` (el core es idempotente por `Idempotency-Key`, así que reintentar
devuelve el resultado original sin descontar dos veces).

## Estructura

```
com.bancoxyz.bff.cajero
├── BffCajeroApplication.java
├── adaptador/    AdaptadorCajero (formatos de pantalla del cajero)
├── config/       ConfiguracionGeneral, DescubrimientoDelCore, FiltroCorrelacion, PropiedadesCajero/Core
├── core/         ClienteCoreCajero, CircuitoDelCoreAbierto, CoreNoRegistrado y los DTO de lectura del core
├── error/        Acciones, ErrorDeCajero, ManejadorDeErroresCajero, TraductorDeErroresDelCore
├── operacion/    ControladorDeOperaciones, PantallaSaldo, SolicitudRetiro, ComprobanteRetiro
├── seguridad/    FiltroCertificadoDeTerminal, FiltroFirmaDeTerminal, FiltroSesionDeTerminal, EmisorDeSesiones,
│                 RegistroDeRevocaciones, FirmaHmac
└── sesion/       ControladorDeSesiones, Luhn, SesionDeTarjeta, SolicitudSesion
```

## Construir y probar

```bash
./mvnw verify
```

Informe de cobertura JaCoCo en `target/site/jacoco/index.html`.

## Ejecutar solo

```bash
BANCO_CERTIFICADOS=../banco-xyz-cloud/certificados java -jar target/bff-cajero-1.0.0.jar
```

Con Config Server y Eureka disponibles, se puede fijar además `BANCO_CONFIG_SERVER` si no corren en
`http://127.0.0.1:8888`. Para levantar el ecosistema completo (Config Server, Eureka, core y los tres BFF) usar
`banco-xyz-cloud/scripts/levantar_servicios.sh`.

## Pruebas destacadas

- `CircuitoDelCoreTest` — sesión y circuito cerrado, política del circuito, circuito abierto en un retiro, y que
  solo las fallas del servidor (no las de negocio) cuentan.
- `DescubrimientoDelCoreTest` — el core se encuentra por nombre vía Spring Cloud LoadBalancer contra un
  `DiscoveryClient` simple, manteniendo el mTLS.
- `TlsDelBffCajeroTest` — el terminal abre mTLS con su certificado y firma; el BFF abre mTLS hacia un core simulado
  que exige certificado.
- `ContratoConsumidorTest` — los records que el cajero usa para leer al core se construyen desde
  `contratos/core-api` sin campos faltantes.
- `CoreSinInstanciasTest` — con descubrimiento activo y ninguna instancia del core en el catálogo, el retiro y el
  inicio de sesión responden `503` y devuelven la tarjeta, nunca `504` con reintento (eso corresponde a un retiro
  que sí se envió).
- `CajeroIntegracionTest` — sesión válida, datos de tarjeta inválidos, saldo, y que la firma y la sesión son
  obligatorias.
- `FiltroFirmaDeTerminalTest` — vectores de la especificación de firma, rechazo por formato, marca de tiempo fuera
  de ventana y firma alterada.

## Enlaces

[README general](../README.md) · [infraestructura Spring Cloud](../banco-xyz-cloud/README.md)
