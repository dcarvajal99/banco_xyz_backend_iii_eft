# clientes-service — Gestión de Clientes

Microservicio dueño de los **clientes** del Banco XYZ: información personal, perfiles y credenciales de acceso
(clientes y ejecutivos). Recibe los eventos de pagos y de seguridad y los convierte en **avisos** para el cliente.

## Ficha

| | |
|---|---|
| Puertos | `8101` HTTPS (negocio) · `9101` HTTP (operación) |
| Nombre en Eureka | `CLIENTES-SERVICE` |
| Configuración | Config Server: `configuracion/clientes-service.properties` + comunes |
| Datos | Esquema `clientes` (cliente, usuario, notificacion, outbox); copia el maestro `migracion.cliente_migrado` |
| Kafka | Consume `cuentas.movimientos` y `seguridad.alertas` · produce `seguridad.alertas` (`UsuarioBloqueado`) |
| Paquete raíz | `com.bancoxyz.clientes` |

## API

| Método | Ruta | Credencial | Para qué |
|---|---|---|---|
| GET | `/api/v1/clientes` | `clientes.leer` (ejecutivo) | Lista de clientes |
| POST | `/api/v1/clientes` | `clientes.administrar` | Alta: `{nombre, edad, perfil{email, telefono, direccion}, usuario, claveInicial}` (ids desde 100001) |
| GET | `/api/v1/clientes/{id}` | `clientes.leer` | Ficha (un cliente solo la suya) |
| PUT | `/api/v1/clientes/{id}/perfil` | `clientes.perfil` | Correo, teléfono y dirección |
| GET | `/api/v1/clientes/{id}/avisos` | `clientes.leer` | Avisos de transacciones y alertas de seguridad |
| POST | `/api/v1/autenticacion/usuarios` | Basic + certificado de banco-auth | Verifica usuario y clave (solo banco-auth) |
| GET | `/api/v1/autenticacion/usuarios/{usuario}` | Basic + certificado de banco-auth | Datos de un usuario vinculado (login con GitHub) |

## Decisiones

- **Las claves viven aquí, no en banco-auth ni en los BFF:** banco-auth las verifica por una puerta propia (Basic +
  mTLS) y nunca guarda una copia. Al quinto intento fallido el usuario se bloquea 15 minutos y se publica `UsuarioBloqueado`.
- **Ids estables:** los clientes migrados conservan el id que asignó el batch; cuentas-service usa el mismo id sin
  llamar a este servicio. Las altas en línea parten en 100001 para no chocar nunca.
- **Avisos idempotentes:** `notificacion.evento_id` es único: un evento entregado dos veces deja un solo aviso.
- **Usuarios de demostración** (`banco.clientes.datos-demo`): uno por cliente migrado (`diana.prince`…) y `ejecutivo`.

## Ejecutar y probar

```bash
./mvnw verify
infra/scripts/microservicios.sh     # desde la raíz, con el ecosistema arriba
```
