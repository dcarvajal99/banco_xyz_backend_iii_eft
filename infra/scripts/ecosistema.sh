#!/usr/bin/env bash
# Evidencia: las diez aplicaciones independientes en contenedores, sanas y registradas, mas los puntos de la
# retroalimentacion de la semana 8 (todo escucha en 0.0.0.0; management, Eureka y Resilience4j en el Config Server).
# Requiere el ecosistema arriba: docker compose up -d --build --wait
set -uo pipefail
source "$(dirname "$0")/lib.sh"

titulo "Ecosistema en contenedores" "Criterio 6 (Docker, escalabilidad) y criterio 7 (despliegue documentado)"

paso "docker compose ps: estado de cada contenedor"
"${COMPOSE[@]}" ps -a --format 'table {{.Service}}\t{{.Status}}\t{{.Ports}}' | sed 's/^/  /'

paso "Las diez aplicaciones: nueve servicios sanos y el batch terminado con codigo 0"
for servicio in config-server eureka-1 eureka-2 banco-auth clientes-service cuentas-service pagos-service bff-web bff-movil bff-cajero; do
  contenedor=$("${COMPOSE[@]}" ps -q "$servicio" | head -1)
  comprobar "$servicio" "$(docker inspect --format '{{.State.Health.Status}}' "$contenedor" 2>/dev/null)" "healthy"
done
batch=$("${COMPOSE[@]}" ps -aq banco-xyz-batch | head -1)
comprobar "banco-xyz-batch (contenedor de una ejecucion)" "$(docker inspect --format '{{.State.Status}} codigo {{.State.ExitCode}}' "$batch")" "exited codigo 0"

paso "Eureka (dos nodos replicados): cada servicio registrado con su id de instancia y su URL de salud"
curl -s -H "Accept: application/json" "$EUREKA1/eureka/apps" | python3 -c '
import json, sys
apps = json.load(sys.stdin)["applications"]["application"]
print("  %-18s %-48s %-6s %s" % ("APLICACION", "INSTANCIA", "ESTADO", "SALUD"))
for app in sorted(apps, key=lambda a: a["name"]):
    for i in app["instance"]:
        print("  %-18s %-48s %-6s %s" % (app["name"].lower(), i["instanceId"][:48], i["status"], i["healthCheckUrl"]))'
instancias=$(curl -s -H "Accept: application/json" "$EUREKA2/eureka/apps" | python3 -c 'import json,sys; print(len(json.load(sys.stdin)["applications"]["application"]))')
comprobar "aplicaciones visibles tambien en el nodo 2 (7 servicios + eureka-server)" "$instancias" "8"

paso "Retro S8 (1): dentro del contenedor cada servicio escucha en todas las interfaces (:::puerto), no en 127.0.0.1"
for servicio in cuentas-service config-server eureka-1; do
  contenedor=$("${COMPOSE[@]}" ps -q "$servicio" | head -1)
  printf '  %s%s%s\n' "$C_B" "$servicio" "$C_0"
  docker exec "$contenedor" netstat -tln 2>/dev/null | awk 'NR>2 && $4 !~ /^127\.0\.0\.11:/ {print "    " $4}' | sort -u
done
loopback=$(for s in cuentas-service clientes-service pagos-service banco-auth bff-web bff-movil bff-cajero config-server; do
  docker exec "$("${COMPOSE[@]}" ps -q "$s" | head -1)" netstat -tln 2>/dev/null | awk 'NR>2 {print $4}'; done | grep -c '^127\.0\.0\.1:' || true)
comprobar "sockets escuchando solo en 127.0.0.1 dentro de los contenedores" "$loopback" "0"

paso "Retro S8 (3): cuentas-service recibe del Config Server sus puertos, management, Eureka y Resilience4j"
certificado servicios/cuentas-service
SIN_CUERPO=2 llamar GET "$CONFIG/cuentas-service/tls,nube" -u "configuracion:${BANCO_CONFIG_CLAVE:-config-secreto-dev}" "${CERT[@]}"
printf '%s' "$CUERPO" | python3 -c '
import json, sys
fuentes = json.load(sys.stdin)["propertySources"]
claves = ["server.port", "management.server.port", "management.endpoints.web.exposure.include", "eureka.instance.instance-id",
          "eureka.instance.health-check-url", "eureka.instance.hostname", "resilience4j.circuitbreaker.instances.clientes.base-config",
          "resilience4j.circuitbreaker.configs.http-interno.sliding-window-size", "banco.cuentas.clientes.url"]
for clave in claves:
    for f in fuentes:
        if clave in f["source"]:
            valor = str(f["source"][clave])
            print("  %-66s %-36s %s" % (clave, valor if len(valor) <= 36 else valor[:35] + "…", f["name"].split("/")[-1].replace(".properties", "")))
            break'
jar=$(docker exec "$("${COMPOSE[@]}" ps -q cuentas-service | head -1)" sh -c 'cat /app/BOOT-INF/classes/application.properties 2>/dev/null' \
  | grep -cE '^(management\.|eureka\.|resilience4j\.)' || true)
comprobar "propiedades de management, eureka o resilience4j dentro del jar de cuentas-service" "$jar" "0"

resumen_final
