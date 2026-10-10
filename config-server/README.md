# config-server — Configuración central (Spring Cloud Config)

Entrega a cada aplicación su configuración desde un solo lugar: puertos, URL de los servicios por nombre, Eureka,
Kafka, JWT, la operación (management) y las **políticas de Resilience4j compartidas**. Ningún jar trae esa
configuración: cambiarla es cambiar un archivo de `configuracion/` y reconstruir esta imagen.

## Ficha

| | |
|---|---|
| Puertos | `8888` HTTPS (publicado en 127.0.0.1) · `9888` HTTP (health) |
| Backend | `native`: la carpeta `configuracion/` viaja dentro de la imagen (`/config`), versionada con el código |
| Seguridad | HTTPS + **certificado de cliente obligatorio** (mTLS) + usuario y clave (Basic) |
| Paquete raíz | `com.bancoxyz.config` |

## `configuracion/`

| Archivo | Contenido |
|---|---|
| `application.properties` | Común a todos: management (puerto aparte, en todas las interfaces), Eureka (id por réplica, URL de salud por IP), Kafka, JWT y las políticas `http-interno` y `publicacion-kafka` |
| `application-nube.properties` | Lo propio del entorno en contenedores: nombres de la red de Docker, base, emisor del JWT y Resilience4j más tolerante |
| `<servicio>.properties` | Uno por aplicación: sus puertos, a quién llama por nombre y qué circuitos usa (siempre con `base-config`) |

Los secretos **no** están aquí: llegan a cada servicio por variables de entorno (`.env`). La prueba
`ConfiguracionCentralTest` lo comprueba, junto con que cada circuito use una política compartida y que no haya dos
servicios con el mismo puerto.

## Ejecutar y probar

```bash
./mvnw verify
curl --cacert ca.crt --cert cuentas-service.crt --key cuentas-service.key -u configuracion:config-secreto-dev \
  https://localhost:8888/cuentas-service/tls,nube       # lo que recibe cuentas-service en contenedores
```
