#!/usr/bin/env bash
# Evidencia: pagos asincronos entre microservicios con Kafka (saga simplificada con outbox y consumidores idempotentes).
#   pagos-service registra el pago (SOLICITADO) y publica PagoSolicitado -> cuentas-service mueve los saldos en una
#   transaccion local y publica TransaccionCompletada/Rechazada -> pagos-service cierra el pago y clientes-service avisa.
set -uo pipefail
source "$(dirname "$0")/lib.sh"

titulo "Saga de pagos con Kafka" "Criterio 4 (microservicios, mensajeria asincrona) y requisito de negocio 2 (pagos en linea)"
USUARIO="${USUARIO:-diana.prince}"
sesion_web "$USUARIO" "$CLAVE_CLIENTES"
ORIGEN=$(cuenta_ahorro_de "$USUARIO")
DESTINO=$(sql "select id from cuentas.cuenta where tipo = 'ahorro' and estado = 'ACTIVA' and cliente_id is not null
               and cliente_id <> (select cliente_id from clientes.usuario where usuario = '$USUARIO') order by id limit 1")

asegurar_saldo "$ORIGEN" 8000

paso "Topicos de la saga (los crea cada servicio con 3 particiones; la autocreacion esta apagada)"
kafka_topicos --list | grep -vE '^__' | sed 's/^/  /'

paso "Transferencia de \$1.000 desde la cuenta $ORIGEN a la $DESTINO: pagos-service responde 202 al instante"
A_ORIGEN=$(saldo "$ORIGEN"); A_DESTINO=$(saldo "$DESTINO")
CLAVE=$(uuid)
llamar POST "$WEB/web/api/pagos/transferencias" "${SESION_WEB[@]}" "${JSON[@]}" -H "Idempotency-Key: $CLAVE" \
  --data-raw "{\"cuentaOrigen\":$ORIGEN,\"cuentaDestino\":$DESTINO,\"monto\":1000}"
esperar 202
PAGO=$(campo id)
comprobar "estado inicial" "$(campo estado)" "SOLICITADO"

paso "El pago termina cuando cuentas-service confirma por Kafka"
esperar_pago "$PAGO"
pago_breve
comprobar "estado final" "$ESTADO_PAGO" "COMPLETADO"
comprobar "debito en origen" "$(python3 -c "print(round($A_ORIGEN - $(saldo "$ORIGEN")))")" "1000"
comprobar "credito en destino" "$(python3 -c "print(round($(saldo "$DESTINO") - $A_DESTINO))")" "1000"
paso "Eventos de ese pago en Kafka (clave = cliente: todos sus eventos van a la misma particion y llegan en orden)"
eventos_de_pago "$PAGO"

paso "Reintento con la misma Idempotency-Key (el usuario apreto dos veces): mismo pago, no se cobra de nuevo"
llamar POST "$WEB/web/api/pagos/transferencias" "${SESION_WEB[@]}" "${JSON[@]}" -H "Idempotency-Key: $CLAVE" \
  --data-raw "{\"cuentaOrigen\":$ORIGEN,\"cuentaDestino\":$DESTINO,\"monto\":1000}" > /dev/null
comprobar "mismo id de pago" "$(campo id)" "$PAGO"
comprobar "saldo de origen sin otro debito" "$(python3 -c "print(round($A_ORIGEN - $(saldo "$ORIGEN")))")" "1000"

paso "Saldo insuficiente: la saga termina en RECHAZADO y no se mueve ningun saldo"
A_ORIGEN=$(saldo "$ORIGEN")
MONTO=$(python3 -c "print(int($A_ORIGEN) + 100000)")
[ "$MONTO" -lt 5000000 ] || MONTO=4900000
llamar POST "$WEB/web/api/pagos/transferencias" "${SESION_WEB[@]}" "${JSON[@]}" -H "Idempotency-Key: $(uuid)" \
  --data-raw "{\"cuentaOrigen\":$ORIGEN,\"cuentaDestino\":$DESTINO,\"monto\":$MONTO}" > /dev/null
esperar_pago "$(campo id)"
pago_breve
comprobar "estado final" "$ESTADO_PAGO" "RECHAZADO"
comprobar "saldo de origen intacto" "$(saldo "$ORIGEN")" "$A_ORIGEN"

paso "Regla antifraude (monto sobre \$5.000.000): pagos-service rechaza y publica OperacionSospechosa"
llamar POST "$WEB/web/api/pagos/transferencias" "${SESION_WEB[@]}" "${JSON[@]}" -H "Idempotency-Key: $(uuid)" \
  --data-raw "{\"cuentaOrigen\":$ORIGEN,\"cuentaDestino\":$DESTINO,\"monto\":6000000}" > /dev/null
FRAUDE=$(campo id)
esperar_pago "$FRAUDE"
pago_breve
comprobar "estado final" "$ESTADO_PAGO" "RECHAZADO"
eventos_de_pago "$FRAUDE"

paso "clientes-service consume cuentas.movimientos y seguridad.alertas y deja avisos al cliente"
sleep 2
SIN_CUERPO=2 llamar GET "$WEB/web/api/panel" "${SESION_WEB[@]}" > /dev/null
python3 - "$CUERPO" <<'PY'
import json, sys
for a in (json.loads(sys.argv[1]).get("avisos") or [])[:4]:
    print("  %s %-24s %s %s" % (a.get("creadaEn", "")[:19], a.get("tipo"), "[seguridad]" if a.get("seguridad") else "           ", a.get("mensaje")))
PY

paso "Entrega al menos una vez: se reinyecta el mismo PagoSolicitado y cuentas-service lo ignora (evento_procesado)"
MENSAJE=$(leer_topico pagos.solicitados 4 | grep "$PAGO" | head -1)
CLAVE_KAFKA=$(printf '%s' "$MENSAJE" | cut -f3)
CUERPO_KAFKA=$(printf '%s' "$MENSAJE" | cut -f4-)
A_ORIGEN=$(saldo "$ORIGEN")
publicar_crudo pagos.solicitados "$CLAVE_KAFKA" "$CUERPO_KAFKA"
sleep 3
comprobar "saldo de origen despues del duplicado" "$(saldo "$ORIGEN")" "$A_ORIGEN"
comprobar "movimientos aplicados para ese pago" "$(sql "select count(*) from cuentas.pago_aplicado where pago_id = '$PAGO'")" "1"

paso "Mensaje ilegible: no bloquea la particion, se aparta en el topico DLT"
publicar_crudo pagos.solicitados "$ORIGEN" '{esto no es un evento'
sleep 4
leer_topico pagos.solicitados.DLT 4 | tail -2 | cut -c1-140 | sed 's/^/  /'

paso "Grupos de consumidores al dia (LAG 0 = nada pendiente)"
grupo_consumidor cuentas-service pagos-service clientes-service

rm -f "$JAR_WEB"
resumen_final
