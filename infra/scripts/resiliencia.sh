#!/usr/bin/env bash
# Evidencia: comportamiento ante fallas con Resilience4j (CircuitBreaker + Retry + Bulkhead y comportamiento alternativo).
# Detiene de verdad un microservicio (docker compose stop) y muestra como responden los demas; al final lo repone.
set -uo pipefail
source "$(dirname "$0")/cajero_lib.sh"

titulo "Resiliencia ante fallas" "Criterio 4 (Resilience4j: evidencia del comportamiento ante fallas)"
USUARIO="${USUARIO:-diana.prince}"
# Las sesiones se abren antes de las fallas: el login tambien depende de clientes-service (banco-auth verifica la clave ahi).
sesion_web "$USUARIO" "$CLAVE_CLIENTES"
JAR_CLIENTE="$JAR_WEB"; SESION_CLIENTE=("${SESION_WEB[@]}")
sesion_web ejecutivo "$CLAVE_EJECUTIVO"
JAR_EJECUTIVO="$JAR_WEB"; SESION_EJECUTIVO=("${SESION_WEB[@]}")
token_movil "$USUARIO"
CUENTA=$(cuenta_ahorro_de "$USUARIO")
CLIENTE=$(sql "select cliente_id from clientes.usuario where usuario = '$USUARIO'")
SESION_WEB=("${SESION_CLIENTE[@]}")
asegurar_saldo "$CUENTA" 5000
cajero POST /cajero/v1/sesiones "{\"numeroTarjeta\":\"$(pan_de_cuenta "$CUENTA")\",\"pin\":\"1234\"}" > /dev/null
TOKEN_CAJERO=$(campo tokenSesion)

paso "Circuitos con todo arriba"
mostrar_circuito bff-web:9090 cuentas bff-web
mostrar_circuito bff-web:9090 clientes bff-web
mostrar_circuito cuentas-service:9100 clientes cuentas-service
mostrar_circuito pagos-service:9102 cuentas pagos-service

# ------------------------------------------------------------------------------------------- falla 1: clientes-service
paso "FALLA 1 · se detiene clientes-service (todas sus instancias)"
"${COMPOSE[@]}" stop clientes-service 2>&1 | sed 's/^/  /'

paso "bff-web: el panel se muestra igual, sin la ficha ni los avisos (degradacion parcial, no un error)"
SIN_CUERPO=2 llamar GET "$WEB/web/api/panel" "${SESION_CLIENTE[@]}"
esperar 200
comprobar "secciones no disponibles" "$(campo noDisponibles)" '["cliente", "avisos"]'
comprobar "cuentas siguen visibles" "$([ "$(campo cuentas.0.cuentaId)" != "" ] && echo si)" "si"

paso "cuentas-service: abrir cuenta a un cliente conocido usa el titular de sus otras cuentas (fallback)"
llamar POST "$WEB/web/api/ejecutivo/cuentas" "${SESION_EJECUTIVO[@]}" "${JSON[@]}" \
  --data-raw "{\"clienteId\":$CLIENTE,\"tipo\":\"ahorro\",\"alias\":\"Abierta sin clientes-service\"}"
esperar 201
registros cuentas-service "clientes-service no disponible" 1

paso "cuentas-service: a un cliente sin cuentas no se le abre nada a ciegas (503 claro, no un 500)"
llamar POST "$WEB/web/api/ejecutivo/cuentas" "${SESION_EJECUTIVO[@]}" "${JSON[@]}" --data-raw '{"clienteId":999999,"tipo":"ahorro"}'
esperar 503 CLIENTES_NO_DISPONIBLE

paso "banco-auth: un login nuevo falla con un mensaje para el usuario; las sesiones abiertas no se ven afectadas"
estado_login=$(curl -s -o /dev/null -w '%{redirect_url}' "${TLS[@]}" -c /dev/null "$AUTH/login" --data-urlencode "username=$USUARIO" \
  --data-urlencode "password=$CLAVE_CLIENTES" --data-urlencode "_csrf=x")
nota "login nuevo -> $estado_login"
for _ in 1 2 3 4; do curl -s -o /dev/null "${TLS[@]}" "${SESION_CLIENTE[@]}" "$WEB/web/api/panel"; done
mostrar_circuito bff-web:9090 clientes bff-web
mostrar_circuito cuentas-service:9100 clientes cuentas-service

paso "Se repone clientes-service"
docker start $("${COMPOSE[@]}" ps -aq clientes-service) > /dev/null && nota "docker start clientes-service"
esperar_sano clientes-service

# -------------------------------------------------------------------------------------------- falla 2: cuentas-service
paso "FALLA 2 · se detiene cuentas-service (dueno de los saldos)"
"${COMPOSE[@]}" stop cuentas-service 2>&1 | sed 's/^/  /'
A_ORIGEN=$(saldo "$CUENTA")

paso "pagos-service acepta igual la transferencia: validacion DIFERIDA y el evento espera en Kafka"
DESTINO=$(sql "select id from cuentas.cuenta where tipo = 'ahorro' and estado = 'ACTIVA' and cliente_id is not null and cliente_id <> $CLIENTE order by id limit 1")
llamar POST "$WEB/web/api/pagos/transferencias" "${SESION_CLIENTE[@]}" "${JSON[@]}" -H "Idempotency-Key: $(uuid)" \
  --data-raw "{\"cuentaOrigen\":$CUENTA,\"cuentaDestino\":$DESTINO,\"monto\":500}"
esperar 202
PAGO=$(campo id)
comprobar "validacion" "$(campo validacion)" "DIFERIDA"
comprobar "estado mientras cuentas-service esta caido" "$(campo estado)" "SOLICITADO"

paso "Cada canal informa la falla a su manera (ProblemDetail 503, nunca un 500 ni una traza)"
llamar GET "$WEB/web/api/panel" "${SESION_CLIENTE[@]}"
esperar 503
llamar GET "$MOVIL/movil/v1/inicio" "${BEARER[@]}"
esperar 503
cajero GET /cajero/v1/saldo "" "" "$TOKEN_CAJERO"
esperar 503

paso "Tras varias fallas el circuito se abre: las siguientes llamadas se cortan sin esperar el timeout"
for _ in 1 2 3 4 5; do curl -s -o /dev/null "${TLS[@]}" "${SESION_CLIENTE[@]}" "$WEB/web/api/panel"; done
mostrar_circuito bff-web:9090 cuentas bff-web
eventos_circuito bff-web:9090 cuentas 4
inicio=$(python3 -c 'import time; print(time.time())')
curl -s -o /dev/null "${TLS[@]}" "${SESION_CLIENTE[@]}" "$WEB/web/api/panel"
nota "respuesta con el circuito abierto en $(python3 -c "import time; print(round((time.time() - $inicio) * 1000))") ms"

paso "Se repone cuentas-service: consume el PagoSolicitado pendiente y la transferencia se completa sola"
docker start $("${COMPOSE[@]}" ps -aq cuentas-service) > /dev/null && nota "docker start cuentas-service"
esperar_sano cuentas-service
SESION_WEB=("${SESION_CLIENTE[@]}")
esperar_pago "$PAGO" 60
pago_breve
comprobar "estado final" "$ESTADO_PAGO" "COMPLETADO"
comprobar "debito aplicado una vez" "$(python3 -c "print(round($A_ORIGEN - $(saldo "$CUENTA")))")" "500"

paso "El circuito de bff-web pasa a HALF_OPEN y vuelve a CLOSED con llamadas exitosas"
# Las llamadas de prueba (3 en HALF_OPEN) se hacen cuando bff-web ya ve la replica repuesta: UP en Eureka y su copia del
# catalogo renovada (5 s) y fuera de la cache del LoadBalancer (5 s). Antes, fallarian y el circuito volveria a OPEN.
esperar_eureka cuentas-service 1
sleep 12
inicio=$SECONDS
until [ "$(curl -s -o /dev/null -w '%{http_code}' "${TLS[@]}" "${SESION_CLIENTE[@]}" "$WEB/web/api/panel")" = "200" ] \
  || [ $((SECONDS - inicio)) -ge 90 ]; do sleep 2; done
for _ in 1 2 3; do curl -s -o /dev/null "${TLS[@]}" "${SESION_CLIENTE[@]}" "$WEB/web/api/panel"; done
nota "panel web de nuevo en 200 a los $((SECONDS - inicio)) s"
mostrar_circuito bff-web:9090 cuentas bff-web
eventos_circuito bff-web:9090 cuentas 4
comprobar "estado del circuito cuentas de bff-web" "$(circuito bff-web:9090 cuentas)" "CLOSED"

rm -f "$JAR_CLIENTE" "$JAR_EJECUTIVO"
resumen_final
