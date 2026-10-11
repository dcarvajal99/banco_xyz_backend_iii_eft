# Instrucciones para ejecutar y probar cada componente

Banco XYZ · EFT Desarrollo Backend III (PBY2203) · Diego Carvajal

Estas instrucciones levantan las diez aplicaciones en contenedores en un equipo local y prueban cada componente. Para
la nube ver [`despliegue.md`](despliegue.md).

## 1. Requisitos

| Herramienta | Versión | Para qué |
|---|---|---|
| Docker con Compose v2 (Docker Desktop o Colima) | Docker 24+ | Construir y correr todo. La VM necesita **8 GB de memoria** y 4 CPU |
| bash, curl y Python 3 | macOS o Linux | Scripts de evidencia (`infra/scripts/`) |
| JDK 17 o superior y Maven 3.9 | Opcional | Correr las pruebas de cada proyecto fuera de Docker |

Puertos libres en el equipo (todos se publican solo en `127.0.0.1`): 8081, 8083, 8090, 8091, 8761, 8762, 8888 y 5439.

## 2. Obtener el código y los secretos

```bash
git clone https://github.com/dcarvajal99/banco_xyz_backend_iii_eft.git
cd banco_xyz_backend_iii_eft
cp .env.ejemplo .env
```

El `.env` es opcional: sin él, el compose usa los mismos valores de desarrollo de `.env.ejemplo`. En la entrega del
curso se adjunta un `.env` con las credenciales de la aplicación OAuth de GitHub ya registrada (botón "Ingresar con
GitHub"); el repositorio público nunca lo incluye. Sin esas credenciales todo funciona igual con usuario y clave.

## 3. Levantar el ecosistema

```bash
docker compose up -d --build --wait
```

La primera vez construye las diez imágenes desde el código (unos 10 a 15 minutos; después usa la caché). Arranca en
este orden, cada uno cuando el anterior está sano:

1. `pki` genera la CA de desarrollo y los certificados en el volumen `certificados` y termina.
2. `db` (PostgreSQL 17) y `kafka` (3.9.1, KRaft).
3. `config-server`, luego `eureka-1` y `eureka-2`.
4. `banco-xyz-batch` migra `fin_legacy_data` a la base y termina con código 0.
5. `banco-auth`, `clientes-service`, `cuentas-service` y `pagos-service`.
6. `bff-web`, `bff-movil` y `bff-cajero`.

```bash
docker compose ps        # 12 contenedores "Up (healthy)" + pki y banco-xyz-batch "Exited (0)"
```

Panel de Eureka: <http://127.0.0.1:8761> (los siete servicios y el otro nodo de Eureka registrados).

## 4. Confiar en la CA de desarrollo

Los servicios usan HTTPS con certificados de la CA de desarrollo que genera `pki`. Para que el navegador no muestre la
advertencia:

```bash
docker run --rm -v banco-xyz_certificados:/c:ro alpine:3.20 cat /c/ca/ca.crt > ca-banco-xyz.crt
# macOS: agregarla al llavero como confiable (pide la clave del equipo)
sudo security add-trusted-cert -d -r trustRoot -k /Library/Keychains/System.keychain ca-banco-xyz.crt
```

También se puede aceptar la advertencia en `https://localhost:8090` y en `https://localhost:8081` (el login). Los
scripts no lo necesitan: copian la CA a `infra/certificados/` y la usan con `--cacert`.

## 5. Usuarios de prueba

| Usuario | Clave | Rol | Dónde entra |
|---|---|---|---|
| `diana.prince`, `john.doe`, `alice.brown`, `bob.johnson`, `charlie.green`, `jane.smith`, `steve.rogers` | `Cliente2026!` | Cliente (los 7 titulares migrados del legacy) | Web y app móvil |
| `ejecutivo` | `Ejecutivo2026!` | Ejecutivo del banco | Solo web (consola del ejecutivo) |
| Tarjeta de una cuenta de ahorro: `4` + número de cuenta en 14 dígitos + dígito Luhn (cuenta 121 → `4000000000001216`) | PIN `1234` | Cliente en el cajero | Cajero (terminales `ATM-001` y `ATM-002`) |

## 6. Probar cada componente

Cada script de `infra/scripts/` prueba un componente contra el ecosistema levantado, imprime cada solicitud con su
respuesta y termina con `Resultado: N verificaciones correctas, 0 fallas`.

| Componente | Cómo probarlo | Qué muestra |
|---|---|---|
| Las 10 aplicaciones | `infra/scripts/estructura.sh` | Diez proyectos Maven sin dependencias entre sí, cada uno con su Dockerfile |
| Contenedores | `infra/scripts/ecosistema.sh` | Salud de cada contenedor, registro en los dos nodos de Eureka, configuración desde el Config Server |
| `banco-xyz-batch` | `infra/scripts/batch_migracion.sh` | Paralelismo y particiones, conciliación, bitácora de rechazos, equivalencia con el legacy, políticas de error |
| `bff-web`, `bff-movil`, `bff-cajero` | `infra/scripts/bff_canales.sh` | Login y respuesta de cada canal; rechazos de seguridad propios de cada uno |
| `clientes-service`, `cuentas-service`, `pagos-service`, `banco-auth` | `infra/scripts/microservicios.sh` | Seguridad en la puerta de cada servicio; alta de cliente, apertura, mantenimiento y cierre de cuenta, perfil |
| Kafka (saga de pagos) | `infra/scripts/saga_pagos.sh` | Transferencia asíncrona, eventos con partición y offset, antifraude, avisos, duplicados y DLT |
| Resilience4j | `infra/scripts/resiliencia.sh` | Detiene clientes-service y cuentas-service de verdad: degradación, fallbacks y circuitos (los repone al final) |
| Escalado horizontal | `infra/scripts/escalabilidad.sh` | Dos réplicas de cada microservicio: Eureka, reparto de carga, particiones de Kafka, caída de una réplica |
| Todo | `infra/scripts/probar_todo.sh` | Las ocho anteriores en orden; guarda cada salida en `infra/evidencias/salidas/` |

### 6.1 Batch (`banco-xyz-batch`)

La carga inicial ya corrió al levantar el compose:

```bash
docker compose logs banco-xyz-batch | tail -40          # resumen por step y partición, conciliación y decisor de calidad
docker compose run --rm --no-deps banco-xyz-batch --job=completa --dataset=semana_3 --corrida=carga-inicial \
  --banco.batch.umbral-rechazo-omision=0.80               # misma corrida: ya terminó, no migra dos veces (código 0)
```

Los tres jobs (`--job=transacciones`, `--job=intereses`, `--job=estados`) también se pueden correr por separado. Códigos
de salida: 0 bien, 1 fallo técnico, 2 parámetros, 3 revisión manual (calidad inaceptable o conciliación que no cuadra).
La equivalencia con el legacy se comprueba con `python3 banco-xyz-batch/scripts/equivalencia_legacy.py <entrada> <salida>`
(lo hace `batch_migracion.sh` con la salida real del contenedor).

### 6.2 Banca web (`bff-web`)

1. Abrir <https://localhost:8090> y pulsar **Ingresar**: el navegador va a `https://localhost:8081/login` (banco-auth).
2. Entrar como `diana.prince` / `Cliente2026!`: panel con cuentas, totales, avisos y últimas operaciones.
3. Transferir, pagar un servicio o depositar desde **Operar**: el pago queda `SOLICITADO` y pasa a `COMPLETADO` o
   `RECHAZADO` cuando la saga termina (la lista de operaciones se actualiza).
4. Salir y entrar como `ejecutivo` / `Ejecutivo2026!`: consola con clientes, alta de cliente, apertura, bloqueo y cierre
   de cuentas, calidad de la migración y reporte de transacciones diarias.

API documentada con OpenAPI en <https://localhost:8090/swagger-ui.html> (también en 8091 y 8083 para los otros BFF).

### 6.3 App móvil (`bff-movil`) y cajero (`bff-cajero`)

No tienen interfaz propia en este proyecto: `bff_canales.sh` actúa como la app (PKCE contra banco-auth y llamadas con el
token) y como el terminal del cajero (certificado `ATM-001`, firma HMAC, tarjeta y PIN). Las funciones `token_movil` de
`infra/scripts/lib.sh` y `cajero` de `infra/scripts/cajero_lib.sh` sirven para probar a mano:

```bash
source infra/scripts/cajero_lib.sh
token_movil diana.prince && curl -s --cacert infra/certificados/ca/ca.crt "${BEARER[@]}" https://localhost:8091/movil/v1/inicio
cajero POST /cajero/v1/sesiones '{"numeroTarjeta":"4000000000001216","pin":"1234"}'
```

### 6.4 Los tres BFF en Postman

`infra/postman/banco-xyz-tres-bff.postman_collection.json` compara los tres canales con el mismo cliente: qué responde
cada BFF (completo, liviano o de pantalla) y con qué credencial (cookie de sesión, token de app o certificado + firma).

1. Importar la colección en Postman (File > Import).
2. **Settings > Certificates > CA certificates**: activar y elegir `infra/certificados/ca/ca.crt`.
3. **Settings > Certificates > Add certificate**: host `localhost`, puerto `8083`, CRT
   `infra/certificados/terminales/ATM-001.crt` y KEY `infra/certificados/terminales/ATM-001.key` (el terminal del cajero).
4. Clic derecho en **0 · Preparar credenciales** > Run: hace el login web completo, el PKCE de la app (token de 5 minutos)
   y abre la sesión del cajero, con un paso por solicitud.
5. **1 · Comparar los tres canales**: enviar cada solicitud y mirar **Body > Visualize**. Las carpetas 2 y 3 muestran los
   rechazos de cada canal y las operaciones (depósito, transferencias con contratos distintos y retiro idempotente).

Por línea de comandos, con Newman:

```bash
npx newman run infra/postman/banco-xyz-tres-bff.postman_collection.json --ssl-extra-ca-certs infra/certificados/ca/ca.crt \
  --ssl-client-cert-list <(echo '[{"name":"ATM-001","matches":["https://localhost:8083/*"],"cert":{"src":"infra/certificados/terminales/ATM-001.crt"},"key":{"src":"infra/certificados/terminales/ATM-001.key"}}]')
```

### 6.5 Escalar a mano

```bash
docker compose up -d --no-deps --scale cuentas-service=2 --scale pagos-service=2 --scale clientes-service=2 \
  cuentas-service pagos-service clientes-service
```

Las réplicas aparecen en Eureka con su propio id; los BFF reparten entre ellas y Kafka reparte las particiones.

## 7. Pruebas automatizadas de cada proyecto

Las pruebas no necesitan Docker (H2 en modo PostgreSQL, Kafka embebido y servidores simulados):

```bash
infra/scripts/verificar_proyectos.sh              # mvn verify en las diez aplicaciones, una por una
cd cuentas-service && ./mvnw verify               # o un proyecto solo; cobertura en target/site/jacoco/index.html
```

## 8. Detener

```bash
docker compose down        # detiene todo; base, Kafka y certificados se conservan en sus volúmenes
docker compose down -v     # además borra los volúmenes: la próxima vez todo nace de nuevo (PKI, migración, tópicos)
```

## 9. Problemas comunes

| Síntoma | Causa | Solución |
|---|---|---|
| Un servicio queda `Restarting` | El Config Server todavía no respondía (fail-fast) | Se reintenta solo; si persiste, `docker compose logs <servicio>` |
| `docker compose up` falla por memoria | La VM de Docker tiene menos de 8 GB | Subir la memoria de Docker Desktop o `colima start --memory 8 --cpu 4` |
| El navegador advierte del certificado | La CA de desarrollo no es de confianza | Paso 4 |
| "Ingresar con GitHub" no aparece | Falta `BANCO_GITHUB_CLIENT_ID` en `.env` | Usar usuario y clave, o completar el `.env` de la entrega |
| Un script falla con "Sin respuesta HTTP" | El ecosistema no está arriba o está arrancando | `docker compose ps` y esperar a que todo esté `healthy` |
