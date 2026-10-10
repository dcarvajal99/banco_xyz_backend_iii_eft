#!/usr/bin/env bash
# Evidencia: escalado horizontal. Los microservicios no publican puertos ni tienen nombre fijo, asi que se pueden
# replicar con --scale: cada replica se registra en Eureka, los BFF reparten con Spring Cloud LoadBalancer y Kafka
# reparte las particiones entre las replicas del mismo grupo de consumidores.
set -uo pipefail
source "$(dirname "$0")/lib.sh"

titulo "Escalado horizontal" "Criterio 6 (contenedores y escalabilidad horizontal)"
REPLICAS="${REPLICAS:-2}"
ESCALAR=(--scale cuentas-service="$REPLICAS" --scale pagos-service="$REPLICAS" --scale clientes-service="$REPLICAS")

instancias_eureka() {
  curl -s -H "Accept: application/json" "$EUREKA1/eureka/apps" | python3 -c '
import json, sys
for app in sorted(json.load(sys.stdin)["applications"]["application"], key=lambda a: a["name"]):
    if app["name"] in ("CUENTAS-SERVICE", "PAGOS-SERVICE", "CLIENTES-SERVICE"):
        ids = ", ".join(sorted(i["instanceId"] + " " + i["status"] + " (" + i["ipAddr"] + ")" for i in app["instance"]))
        print("  %-17s %d instancia(s): %s" % (app["name"].lower(), len(app["instance"]), ids))'
}

# esperar_eureka SERVICIO N: espera a que Eureka tenga N instancias UP del servicio. Registrada no basta: el estado de
# salud llega a Eureka unos segundos despues (cada 5 s) y LoadBalancer solo reparte entre las UP.
esperar_eureka() {
  local app inicio=$SECONDS n=0
  app=$(printf %s "$1" | tr a-z A-Z)
  while [ $((SECONDS - inicio)) -lt 90 ]; do
    n=$(curl -s -H "Accept: application/json" "$EUREKA1/eureka/apps/$app" | python3 -c '
import json, sys
try:
    print(sum(1 for i in json.load(sys.stdin)["application"]["instance"] if i["status"] == "UP"))
except Exception:
    print(0)')
    if [ "$n" -ge "$2" ]; then nota "$1: $n instancias UP en Eureka a los $((SECONDS - inicio)) s"; return 0; fi
    sleep 2
  done
  nota "$1: solo $n instancias UP en Eureka tras 90 s"
  return 1
}

paso "Antes: una instancia de cada microservicio"
instancias_eureka

paso "docker compose up -d --no-deps ${ESCALAR[*]} (sin tocar el resto)"
"${COMPOSE[@]}" up -d --no-deps "${ESCALAR[@]}" cuentas-service pagos-service clientes-service 2>&1 | sed 's/^/  /'
for s in clientes-service cuentas-service pagos-service; do esperar_sano "$s" 240; done
"${COMPOSE[@]}" ps --format 'table {{.Name}}\t{{.Status}}' cuentas-service pagos-service clientes-service | sed 's/^/  /'

paso "Eureka: cada replica registrada con su propio id (nombre del contenedor), su estado e IP"
for s in cuentas-service pagos-service clientes-service; do esperar_eureka "$s" "$REPLICAS"; done
instancias_eureka
for s in cuentas-service pagos-service clientes-service; do
  n=$(curl -s -H "Accept: application/json" "$EUREKA1/eureka/apps/$(printf %s "$s" | tr a-z A-Z)" \
    | python3 -c 'import json,sys; print(sum(1 for i in json.load(sys.stdin)["application"]["instance"] if i["status"] == "UP"))')
  comprobar "instancias UP de $s en Eureka" "$n" "$REPLICAS"
done

paso "Los BFF reparten la carga entre las replicas (Spring Cloud LoadBalancer, round robin)"
sesion_web diana.prince "$CLAVE_CLIENTES"
sleep 12   # los BFF renuevan su copia del catalogo cada 5 s y el LoadBalancer la guarda 5 s
marca=$(date -u +%Y-%m-%dT%H:%M:%SZ)
for _ in $(seq 1 20); do curl -s -o /dev/null "${TLS[@]}" "${SESION_WEB[@]}" "$WEB/web/api/panel"; done
sleep 1
nota "20 paneles web -> solicitudes atendidas por cada replica:"
for s in cuentas-service clientes-service pagos-service; do
  "${COMPOSE[@]}" logs --no-color --since "$marca" "$s" 2>/dev/null | grep "FiltroCorrelacion" | grep -E "GET /api/v1/(clientes|pagos)" \
    | awk -F'|' '{gsub(/ /, "", $1); print $1}' | sort | uniq -c | sed 's/^/    /'
done
replicas_con_trafico=$("${COMPOSE[@]}" logs --no-color --since "$marca" cuentas-service 2>/dev/null | grep "FiltroCorrelacion" \
  | awk -F'|' '{print $1}' | sort -u | wc -l | tr -d ' ')
comprobar "replicas de cuentas-service que atendieron trafico" "$replicas_con_trafico" "$REPLICAS"

paso "Kafka reparte las 3 particiones de cada topico entre las replicas del mismo grupo"
sleep 5
grupo_consumidor cuentas-service pagos-service

paso "Varias replicas sincronizan la migracion al arrancar sin duplicar (insert ... on conflict do nothing)"
tabla_sql "select 'cuentas.cuenta (migradas)' tabla, count(*) filas from cuentas.cuenta where origen = 'MIGRACION'
           union all select 'clientes.cliente (migrados)', count(*) from clientes.cliente where origen = 'MIGRACION'
           union all select 'cuentas.tarjeta', count(*) from cuentas.tarjeta"

paso "Una replica cae: el servicio sigue respondiendo con la otra"
victima=$("${COMPOSE[@]}" ps -q cuentas-service | head -1)
nota "se detiene $(docker inspect --format '{{.Name}}' "$victima" | tr -d /)"
docker stop "$victima" > /dev/null
ok=0
for _ in $(seq 1 10); do
  [ "$(curl -s -o /dev/null -w '%{http_code}' "${TLS[@]}" "${SESION_WEB[@]}" "$WEB/web/api/panel")" = "200" ] && ok=$((ok + 1))
  sleep 1
done
comprobar "paneles respondidos (de 10) con una replica caida" "$ok" "10"
docker start "$victima" > /dev/null

paso "Vuelta a una replica por servicio (libera memoria del equipo)"
"${COMPOSE[@]}" up -d --no-deps --scale cuentas-service=1 --scale pagos-service=1 --scale clientes-service=1 \
  cuentas-service pagos-service clientes-service 2>&1 | sed 's/^/  /'

rm -f "$JAR_WEB"
resumen_final
