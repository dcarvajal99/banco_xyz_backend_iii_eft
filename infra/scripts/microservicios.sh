#!/usr/bin/env bash
# Evidencia: tres microservicios independientes (clientes, cuentas, pagos), cada uno dueno de su esquema, protegidos
# en su propia puerta (JWT de banco-auth + certificado del llamante) y con las operaciones del negocio.
set -uo pipefail
source "$(dirname "$0")/lib.sh"

titulo "Microservicios: seguridad en cada puerta y operaciones del negocio" \
  "Criterio 4 (microservicios con Spring Cloud) y requisitos de negocio 1 y 3 (gestion de clientes y cuentas)"
CUENTA="${CUENTA:-101}"

paso "Cada servicio es dueno de su esquema (sin claves foraneas entre esquemas)"
tabla_sql "select table_schema esquema, count(*) tablas, string_agg(table_name, ', ' order by table_name) nombres
           from information_schema.tables where table_schema in ('clientes', 'cuentas', 'pagos', 'migracion')
             and table_name not like 'flyway%' group by table_schema order by 1"

# ------------------------------------------------------------------------------------------------ puerta del servicio
paso "PUERTA · sin token: 401 (el servicio no confia en la red interna)"
llamar_interno GET "$CUENTAS_INTERNO/cuentas/$CUENTA"
esperar 401 NO_AUTENTICADO

paso "PUERTA · token de pagos-service (client_credentials, scope cuentas.leer) sin su certificado: 401"
token_servicio pagos-service "$SECRETO_PAGOS" cuentas.leer
claims_jwt "$TOKEN_SERVICIO"
llamar_interno GET "$CUENTAS_INTERNO/cuentas/$CUENTA" -H "Authorization: Bearer $TOKEN_SERVICIO"
esperar 401 CERTIFICADO_REQUERIDO

paso "PUERTA · el mismo token con el certificado de OTRO servicio: 403 (token robado no sirve sin la clave privada)"
llamar_interno GET "$CUENTAS_INTERNO/cuentas/$CUENTA" -H "Authorization: Bearer $TOKEN_SERVICIO" \
  --cert /certificados/servicios/bff-movil.crt --key /certificados/servicios/bff-movil.key
esperar 403 CERTIFICADO_NO_CORRESPONDE

paso "PUERTA · token + certificado de pagos-service: 200"
SIN_CUERPO=1 llamar_interno GET "$CUENTAS_INTERNO/cuentas/$CUENTA" -H "Authorization: Bearer $TOKEN_SERVICIO" \
  --cert /certificados/servicios/pagos-service.crt --key /certificados/servicios/pagos-service.key
esperar 200

paso "PUERTA · certificado firmado por una CA desconocida: el TLS se corta"
llamar_interno GET "$CUENTAS_INTERNO/cuentas/$CUENTA" -H "Authorization: Bearer $TOKEN_SERVICIO" \
  --cert /certificados/intruso/pagos-service-falso.crt --key /certificados/intruso/pagos-service-falso.key
comprobar "conexion" "$([ "$ESTADO" = "000" ] || [ "$ESTADO" = "401" ] && echo rechazada)" "rechazada"

paso "PUERTA · token de un cliente usado directo (sin pasar por un BFF): 403"
token_movil diana.prince
llamar_interno GET "$CUENTAS_INTERNO/cuentas/$CUENTA" -H "Authorization: Bearer $TOKEN" \
  --cert /certificados/servicios/pagos-service.crt --key /certificados/servicios/pagos-service.key
esperar 403 CERTIFICADO_NO_CORRESPONDE

paso "PUERTA · un cliente no tiene scopes de ejecutivo: abrir una cuenta aunque llegue por un BFF autorizado es 403"
llamar_interno POST "$CUENTAS_INTERNO/cuentas" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  --cert /certificados/servicios/bff-movil.crt --key /certificados/servicios/bff-movil.key \
  --data-raw '{"clienteId":4,"tipo":"ahorro"}'
esperar 403

# ----------------------------------------------------------------------------------------------- operaciones del negocio
paso "EJECUTIVO · inicia sesion en la web (scopes de administracion; no puede pagar)"
sesion_web ejecutivo "$CLAVE_EJECUTIVO"
JAR_EJECUTIVO="$JAR_WEB"; SESION_EJECUTIVO=("${SESION_WEB[@]}")
SIN_CUERPO=2 llamar GET "$WEB/web/api/ejecutivo/clientes" "${SESION_EJECUTIVO[@]}"
esperar 200
printf '%s' "$CUERPO" | python3 -c '
import json, sys
for c in json.load(sys.stdin)[:8]:
    print("  %6s  %-16s %-14s %s" % (c["clienteId"], c["nombre"], c.get("usuario") or "", c.get("origen")))'

paso "EJECUTIVO · alta de un cliente nuevo (clientes-service); su id parte en 100001, lejos de los migrados"
SUFIJO=$(date +%H%M%S)
NUEVO_USUARIO="cliente.eft$SUFIJO"
CLAVE_NUEVA="ClaveInicial-$SUFIJO"
llamar POST "$WEB/web/api/ejecutivo/clientes" "${SESION_EJECUTIVO[@]}" "${JSON[@]}" \
  --data-raw "{\"nombre\":\"Cliente EFT $SUFIJO\",\"edad\":34,\"perfil\":{\"email\":\"eft$SUFIJO@correo.cl\",\"telefono\":null,\"direccion\":null},\"usuario\":\"$NUEVO_USUARIO\",\"claveInicial\":\"$CLAVE_NUEVA\"}"
esperar 201
NUEVO=$(campo clienteId)

paso "EJECUTIVO · abre una cuenta de ahorro al cliente nuevo (cuentas-service verifica al titular en clientes-service)"
llamar POST "$WEB/web/api/ejecutivo/cuentas" "${SESION_EJECUTIVO[@]}" "${JSON[@]}" \
  --data-raw "{\"clienteId\":$NUEVO,\"tipo\":\"ahorro\",\"alias\":\"Cuenta principal\"}"
esperar 201
CUENTA_NUEVA=$(campo cuentaId)

paso "EJECUTIVO · mantenimiento: bloquea la cuenta y la vuelve a activar"
llamar PATCH "$WEB/web/api/ejecutivo/cuentas/$CUENTA_NUEVA" "${SESION_EJECUTIVO[@]}" "${JSON[@]}" --data-raw '{"estado":"BLOQUEADA"}'
esperar 200
comprobar "estado" "$(campo estado)" "BLOQUEADA"
SIN_CUERPO=2 llamar PATCH "$WEB/web/api/ejecutivo/cuentas/$CUENTA_NUEVA" "${SESION_EJECUTIVO[@]}" "${JSON[@]}" --data-raw '{"estado":"ACTIVA","alias":"Ahorro EFT"}'
comprobar "estado" "$(campo estado)" "ACTIVA"

paso "CLIENTE NUEVO · entra a la web con su clave inicial y ve su cuenta"
sesion_web "$NUEVO_USUARIO" "$CLAVE_NUEVA"
SIN_CUERPO=2 llamar GET "$WEB/web/api/panel" "${SESION_WEB[@]}"
esperar 200
comprobar "cuenta nueva en su panel" "$(campo cuentas.0.cuentaId)" "$CUENTA_NUEVA"

paso "CLIENTE NUEVO · actualiza su perfil; un correo invalido se rechaza con el detalle por campo"
llamar PUT "$WEB/web/api/perfil" "${SESION_WEB[@]}" "${JSON[@]}" --data-raw "{\"email\":\"eft$SUFIJO@correo.cl\",\"telefono\":\"+56 9 5555 1234\",\"direccion\":\"Av. Siempre Viva 742\"}"
esperar 200
llamar PUT "$WEB/web/api/perfil" "${SESION_WEB[@]}" "${JSON[@]}" --data-raw '{"email":"no-es-un-correo"}'
esperar 400

paso "CLIENTE NUEVO · no puede usar la consola del ejecutivo"
llamar GET "$WEB/web/api/ejecutivo/clientes" "${SESION_WEB[@]}"
esperar 403

paso "EJECUTIVO · cierra la cuenta (saldo cero); una cuenta cerrada no admite operaciones"
llamar POST "$WEB/web/api/ejecutivo/cuentas/$CUENTA_NUEVA/cierre" "${SESION_EJECUTIVO[@]}" "${JSON[@]}" --data-raw '{"motivo":"Solicitud del cliente"}'
esperar 200
comprobar "estado" "$(campo estado)" "CERRADA"
llamar PATCH "$WEB/web/api/ejecutivo/cuentas/$CUENTA_NUEVA" "${SESION_EJECUTIVO[@]}" "${JSON[@]}" --data-raw '{"estado":"ACTIVA"}'
esperar 409

paso "EJECUTIVO · reportes que alimenta el batch (calidad de la migracion y transacciones diarias)"
SIN_CUERPO=2 llamar GET "$WEB/web/api/ejecutivo/reportes/calidad" "${SESION_EJECUTIVO[@]}"
printf '%s' "$CUERPO" | python3 -m json.tool --no-ensure-ascii 2>/dev/null | head -20 | sed 's/^/  /'

rm -f "$JAR_WEB" "$JAR_EJECUTIVO"
resumen_final
