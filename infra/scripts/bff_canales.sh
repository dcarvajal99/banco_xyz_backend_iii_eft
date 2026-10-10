#!/usr/bin/env bash
# Evidencia: tres BFF independientes, uno por canal, con respuesta y seguridad propias.
#   web    -> token handler: login OAuth2 (authorization_code + PKCE) hecho por el BFF; el navegador solo ve una cookie.
#   movil  -> la app es un cliente publico con PKCE; el BFF valida el JWT (aud y azp) y entrega respuestas livianas.
#   cajero -> el terminal se identifica con certificado (mTLS) y firma HMAC; el cliente con tarjeta y PIN.
set -uo pipefail
source "$(dirname "$0")/cajero_lib.sh"

titulo "Tres BFF: una API por canal" "Criterio 3 (BFF con respuestas y seguridad por canal)"
USUARIO="${USUARIO:-diana.prince}"

# ------------------------------------------------------------------------------------------------------------- web
paso "WEB · sin sesion la API no responde datos (401 en JSON, no una pagina de login)"
llamar GET "$WEB/web/api/panel"
esperar 401

paso "WEB · login de $USUARIO: el BFF hace authorization_code + PKCE con banco-auth y guarda los tokens en su sesion"
sesion_web "$USUARIO" "$CLAVE_CLIENTES"
nota "Cookies que recibe el navegador (ningun token):"
grep -E 'BFF_WEB_SESION|XSRF-TOKEN' "$JAR_WEB" | awk '{printf "    %-16s HttpOnly=%s Secure=%s\n", $6, ($1 ~ /^#HttpOnly_/ ? "si" : "no"), ($4=="TRUE" ? "si" : "no")}'
llamar GET "$WEB/web/api/sesion" "${SESION_WEB[@]}"
esperar 200
comprobar "rol de la sesion web" "$(campo rol)" "CLIENTE"
asegurar_saldo "$(cuenta_ahorro_de "$USUARIO")" 8000

paso "WEB · el panel junta 4 llamadas a 3 microservicios en una respuesta completa para la pantalla grande"
SIN_CUERPO=1 llamar GET "$WEB/web/api/panel" "${SESION_WEB[@]}"
esperar 200
PANEL="$CUERPO"
python3 - "$PANEL" <<'PY'
import json, sys
p = json.loads(sys.argv[1])
print("  cliente: %s · cuentas: %d · disponible: %s · deuda: %s · avisos: %d · secciones no disponibles: %s" % (
    p["cliente"]["nombre"], len(p["cuentas"]), p["disponible"], p["deuda"], len(p.get("avisos") or []), p["noDisponibles"]))
print("  campos de cada cuenta en web: " + ", ".join(p["cuentas"][0].keys()))
PY

paso "WEB · una escritura sin el token CSRF se rechaza (la cookie sola no basta)"
llamar PUT "$WEB/web/api/perfil" -b "$JAR_WEB" "${JSON[@]}" --data-raw '{"telefono":"+56 9 1111 2222"}'
esperar 403 CSRF_INVALIDO

# ----------------------------------------------------------------------------------------------------------- movil
paso "MOVIL · la app obtiene su token con PKCE (cliente publico banca-movil, sin secreto)"
token_movil "$USUARIO"
comprobar "token emitido para la app" "$([ -n "$TOKEN" ] && echo si)" "si"
claims_jwt "$TOKEN"

paso "MOVIL · portada liviana: solo lo que cabe en la pantalla, comprimida con gzip y con ETag"
SIN_CUERPO=1 llamar GET "$MOVIL/movil/v1/inicio" "${BEARER[@]}" --compressed
esperar 200
ETAG=$(encabezado etag)
printf '%s' "$CUERPO" | python3 -c '
import json, sys
p = json.load(sys.stdin)
print("  campos de la portada: " + ", ".join(p.keys()))
cuentas = p.get("cuentas") or []
if cuentas:
    print("  campos de cada cuenta en movil: " + ", ".join(cuentas[0].keys()))'
BEARER_CLIENTE=("${BEARER[@]}")
web=$(bytes_de "$WEB/web/api/panel" "${SESION_WEB[@]}")
web_gzip=$(bytes_de "$WEB/web/api/panel" "${SESION_WEB[@]}" -H "Accept-Encoding: gzip")
movil=$(bytes_de "$MOVIL/movil/v1/inicio" "${BEARER[@]}")
movil_gzip=$(bytes_de "$MOVIL/movil/v1/inicio" "${BEARER[@]}" -H "Accept-Encoding: gzip")
nota "bytes del mismo cliente: panel web $web ($web_gzip con gzip) · portada movil $movil ($movil_gzip con gzip)"

paso "MOVIL · si nada cambio, If-None-Match devuelve 304 sin cuerpo (ahorra datos moviles)"
llamar GET "$MOVIL/movil/v1/inicio" "${BEARER[@]}" -H "If-None-Match: $ETAG"
esperar 304

paso "MOVIL · un token valido de otro cliente OAuth (bff-cajero) no entra: azp distinto de banca-movil"
token_servicio bff-cajero "$SECRETO_CAJERO" cajero.operar
llamar GET "$MOVIL/movil/v1/inicio" -H "Authorization: Bearer $TOKEN_SERVICIO"
esperar 401

paso "MOVIL · un ejecutivo no puede usar la app movil: banco-auth niega la autorizacion (access_denied)"
token_movil ejecutivo "openid cuentas.leer" "$CLAVE_EJECUTIVO" || true
comprobar "error devuelto a la app" "$ERROR_MOVIL" "access_denied"

# ---------------------------------------------------------------------------------------------------------- cajero
CUENTA=$(cuenta_ahorro_de "$USUARIO")
PAN=$(pan_de_cuenta "$CUENTA")
paso "CAJERO · el terminal ATM-001 (certificado + firma HMAC) abre sesion con tarjeta y PIN de la cuenta $CUENTA"
cajero POST /cajero/v1/sesiones "{\"numeroTarjeta\":\"$PAN\",\"pin\":\"1234\"}"
esperar 201
TOKEN_CAJERO=$(campo tokenSesion)

paso "CAJERO · saldo en formato de pantalla del cajero"
cajero GET /cajero/v1/saldo "" "" "$TOKEN_CAJERO"
esperar 200
ANTES=$(saldo "$CUENTA")

paso "CAJERO · retiro con Idempotency-Key; el reintento del terminal devuelve el mismo comprobante sin descontar dos veces"
CLAVE_RETIRO=$(uuid)
cajero POST /cajero/v1/retiros '{"monto":1000}' "$CLAVE_RETIRO" "$TOKEN_CAJERO"
esperar 201
COMPROBANTE=$(campo comprobante)
cajero POST /cajero/v1/retiros '{"monto":1000}' "$CLAVE_RETIRO" "$TOKEN_CAJERO"
comprobar "mismo comprobante en el reintento" "$(campo comprobante)" "$COMPROBANTE"
comprobar "descuento en la cuenta $CUENTA" "$(python3 -c "print(round($ANTES - $(saldo "$CUENTA")))")" "1000"

paso "CAJERO · sin certificado de terminal: 401 y la pantalla ordena devolver la tarjeta (no se atiende a un equipo desconocido)"
SIN_CERTIFICADO=1 cajero GET /cajero/v1/saldo "" "" "$TOKEN_CAJERO"
esperar 401 CERTIFICADO_TERMINAL_REQUERIDO

paso "CAJERO · firma alterada: el BFF rechaza la solicitud aunque el certificado sea valido"
FIRMA_FORZADA="AAAA$(printf '%s' "$COMPROBANTE" | base64 | head -c 20)" cajero GET /cajero/v1/saldo "" "" "$TOKEN_CAJERO"
esperar 401 FIRMA_INVALIDA

paso "CAJERO · PIN incorrecto: mensaje para la pantalla y cuantos intentos quedan"
cajero POST /cajero/v1/sesiones "{\"numeroTarjeta\":\"$PAN\",\"pin\":\"9999\"}"
esperar 401 PIN_INCORRECTO

paso "Mismo dato, tres formas: la cuenta $CUENTA en cada canal"
python3 - "$PANEL" "$CUENTA" <<'PY'
import json, sys
p, cuenta = json.loads(sys.argv[1]), int(sys.argv[2])
c = next((c for c in p["cuentas"] if c.get("cuentaId") == cuenta), p["cuentas"][0])
print("  web    (%2d campos): %s..." % (len(c), json.dumps(c, ensure_ascii=False)[:120]))
PY
SIN_CUERPO=2 llamar GET "$MOVIL/movil/v1/inicio" "${BEARER_CLIENTE[@]}" > /dev/null
python3 - "$CUERPO" "$CUENTA" <<'PY'
import json, sys
p, cuenta = json.loads(sys.argv[1]), int(sys.argv[2])
c = next((c for c in p["cuentas"] if c.get("id") == cuenta), p["cuentas"][0])
print("  movil  (%2d campos): %s" % (len(c), json.dumps(c, ensure_ascii=False)))
PY
SIN_CUERPO=2 cajero GET /cajero/v1/saldo "" "" "$TOKEN_CAJERO" > /dev/null
printf '  cajero (pantalla) : %s\n' "$CUERPO"
nota "la portada movil guarda las cuentas 10 s en cache (banco.movil.cache-cuentas-segundos): puede mostrar el saldo previo al retiro"

rm -f "$JAR_WEB"
resumen_final
