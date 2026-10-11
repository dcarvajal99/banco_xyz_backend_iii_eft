# infra — PKI de desarrollo y scripts de evidencia

No contiene lógica de negocio. Tiene lo que el `docker-compose.yaml` de la raíz necesita además de las diez
aplicaciones, y los scripts que prueban el ecosistema levantado.

| Carpeta | Qué hay |
|---|---|
| `docker/pki/` | Imagen de una ejecución: genera la CA de desarrollo y los certificados en el volumen `certificados` (solo la primera vez) y los deja legibles solo para el uid 10001 de las imágenes |
| `scripts/generar_certificados.sh` | Lo que corre esa imagen: un certificado por servicio (CN y SAN = nombre del servicio, `localhost`, `127.0.0.1`), uno por terminal de cajero (`ATM-001`, `ATM-002`) y un certificado intruso firmado por otra CA (para las pruebas negativas) |
| `scripts/*.sh` | Evidencias: cada una prueba un criterio de la pauta y termina con `Resultado: N verificaciones correctas, 0 fallas` |
| `evidencias/` | Salidas de las evidencias (no se versiona; acompaña la entrega) |
| `postman/` | Colección de Postman de los tres BFF: login de cada canal, comparación, rechazos y operaciones (ver `instrucciones.md` 6.4) |
| `certificados/` | Copia local de la PKI que hacen los scripts (no se versiona: tiene claves privadas) |

## Scripts

| Script | Prueba |
|---|---|
| `estructura.sh` | Diez proyectos Maven independientes, sin dependencias entre sí, cada uno con su Dockerfile |
| `ecosistema.sh` | Salud de los contenedores, Eureka replicado, nada escucha solo en 127.0.0.1, configuración desde el Config Server |
| `batch_migracion.sh` | Paralelismo, conciliación, bitácora, equivalencia con el legacy y políticas de error del batch |
| `bff_canales.sh` | Web (token handler + CSRF), móvil (PKCE + azp + ETag) y cajero (mTLS + HMAC + PIN) |
| `microservicios.sh` | Seguridad en la puerta de cada servicio y las operaciones de clientes y cuentas |
| `saga_pagos.sh` | Pagos asíncronos por Kafka: eventos, antifraude, avisos, duplicados y DLT |
| `resiliencia.sh` | Detiene clientes-service y cuentas-service: degradación, fallbacks y circuitos (los repone) |
| `escalabilidad.sh` | Dos réplicas de cada microservicio: Eureka, reparto de carga, particiones y caída de una réplica |
| `probar_todo.sh` | Las ocho anteriores en orden, con la salida de cada una en `evidencias/salidas/` |
| `verificar_proyectos.sh` | `mvn verify` en cada aplicación por separado |
| `lib.sh`, `cajero_lib.sh`, `firmar_cajero.sh` | Funciones comunes: llamadas con su respuesta, login web, token PKCE de la app, terminal de cajero |

Todas las llamadas HTTPS validan el certificado con la CA del banco (`--cacert`, nunca `-k`). Lo que no se publica
fuera (los microservicios, los puertos de operación, Kafka) se consulta desde un contenedor conectado a la red
`banco-xyz`.
