# eureka-server — Descubrimiento de servicios (Spring Cloud Netflix Eureka)

Catálogo donde cada aplicación se registra al arrancar y donde los clientes buscan a los demás por nombre
(`https://cuentas-service/api/v1` → una instancia sana, elegida por Spring Cloud LoadBalancer).

## Ficha

| | |
|---|---|
| Puertos | `8761` (eureka-1) y `8762` (eureka-2), publicados en 127.0.0.1 |
| Imagen | Una sola; el perfil decide el nodo: `nube-peer1` o `nube-peer2` |
| Paquete raíz | `com.bancoxyz.eureka` |

## Decisiones

- **Dos nodos que se replican:** cada uno es cliente del otro; si uno cae, los servicios siguen con el otro (su
  `defaultZone` lista los dos).
- **Una entrada por réplica:** el id de instancia es `servicio:HOSTNAME:puerto` y la URL de salud usa la IP de la
  réplica y su puerto de operación; con `--scale` cada contenedor aparece por separado.
- **Catálogo fresco:** renovación cada 5 s y vencimiento a los 15 s; los clientes refrescan su copia cada 5 s.

## Ejecutar y probar

```bash
./mvnw verify
open http://127.0.0.1:8761        # panel, con el ecosistema arriba
```
