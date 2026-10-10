#!/usr/bin/env bash
# Funciones comunes de los scripts de evidencia del Banco XYZ (EFT: diez aplicaciones en contenedores).
# Imprimen cada solicitud y su respuesta de forma legible para las capturas.
# Uso: source "$(dirname "$0")/lib.sh"
#
# Desde el equipo solo se alcanza lo que el compose publica en 127.0.0.1 (los tres BFF, banco-auth, Config Server,
# Eureka y la base). Los microservicios viven en la red interna: se consultan desde un contenedor de curl conectado a
# ella (llamar_interno). Cada llamada HTTPS valida el certificado contra la CA del banco (--cacert, nunca -k).

RAIZ_PROYECTO="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/.." && pwd)"
RAIZ_REPO="$(cd "$RAIZ_PROYECTO/.." && pwd)"
COMPOSE=(docker compose --project-directory "$RAIZ_REPO" -f "$RAIZ_REPO/docker-compose.yaml")
CERTS="${BANCO_CERTIFICADOS:-$RAIZ_PROYECTO/certificados}"
RED="banco-xyz"
VOLUMEN_CERTIFICADOS="banco-xyz_certificados"
IMAGEN_CURL="curlimages/curl:8.11.1"
IMAGEN_KAFKA="apache/kafka:3.9.1"
# Herramientas de Kafka en un contenedor efimero de la red, nunca dentro del broker (comparten su limite de memoria).
KAFKA_CLI=(docker run --rm --network "$RED" -e KAFKA_HEAP_OPTS="-Xms32m -Xmx128m" "$IMAGEN_KAFKA")
KAFKA_CLI_ENTRADA=(docker run --rm -i --network "$RED" -e KAFKA_HEAP_OPTS="-Xms32m -Xmx128m" "$IMAGEN_KAFKA")

CONFIG="https://localhost:8888"
EUREKA1="http://127.0.0.1:8761"
EUREKA2="http://127.0.0.1:8762"
AUTH="https://localhost:8081"
WEB="https://localhost:8090"
MOVIL="https://localhost:8091"
CAJERO="https://localhost:8083"
CUENTAS_INTERNO="https://cuentas-service:8100/api/v1"
CLIENTES_INTERNO="https://clientes-service:8101/api/v1"
PAGOS_INTERNO="https://pagos-service:8102/api/v1"
AUTH_INTERNO="https://banco-auth:8081"
CLAVE_CLIENTES="${BANCO_DEMO_CLAVE_CLIENTES:-Cliente2026!}"
CLAVE_EJECUTIVO="${BANCO_DEMO_CLAVE_EJECUTIVO:-Ejecutivo2026!}"
SECRETO_PAGOS="${BANCO_OAUTH_PAGOS_SECRETO:-pagos-oauth-dev}"
SECRETO_CAJERO="${BANCO_OAUTH_BFF_CAJERO_SECRETO:-bff-cajero-oauth-dev}"
SECRETO_OPERACION="${BANCO_OAUTH_OPERACION_SECRETO:-operacion-secreto-dev}"
REDIRECCION_MOVIL="http://127.0.0.1:8099/callback"
JSON=(-H "Content-Type: application/json")

# copiar_volumen VOLUMEN DESTINO: copia el contenido de un volumen del compose a una carpeta del equipo. Con docker cp
# (por la API de Docker): un bind mount de una carpeta del Mac no siempre llega a la VM de Colima.
copiar_volumen() {
  local id
  mkdir -p "$2"
  id=$(docker create -v "$1:/origen:ro" alpine:3.20 true)
  docker cp -q "$id:/origen/." "$2/" > /dev/null 2>&1
  docker rm "$id" > /dev/null
}

# obtener_certificados: copia la PKI del volumen del compose a la carpeta local (la genera la imagen pki).
obtener_certificados() {
  copiar_volumen "$VOLUMEN_CERTIFICADOS" "$CERTS"
}
# Se recopia si falta o si el volumen tiene otra CA (p. ej. despues de docker compose down -v).
ca_volumen=$(docker run --rm -v "$VOLUMEN_CERTIFICADOS:/origen:ro" alpine:3.20 cat /origen/ca/ca.crt 2>/dev/null)
if [ ! -f "$CERTS/ca/ca.crt" ] || [ "$ca_volumen" != "$(cat "$CERTS/ca/ca.crt")" ]; then obtener_certificados; fi
TLS=(--cacert "$CERTS/ca/ca.crt")

if [ -n "${NO_COLOR:-}" ]; then
  C_TIT=""; C_OK=""; C_ERR=""; C_DIM=""; C_B=""; C_AMA=""; C_0=""
else
  C_TIT=$'\033[1;36m'; C_OK=$'\033[1;32m'; C_ERR=$'\033[1;31m'; C_DIM=$'\033[2m'; C_B=$'\033[1m'; C_AMA=$'\033[1;33m'; C_0=$'\033[0m'
fi

OK=0
FALLAS=0

# titulo "Evidencia N - descripcion" "Criterio de la pauta que demuestra"
titulo() {
  printf '\n%s══════ %s ══════%s\n' "$C_TIT" "$1" "$C_0"
  if [ -n "${2:-}" ]; then printf '%s%s%s\n' "$C_DIM" "$2" "$C_0"; fi
}

# paso "Que se va a probar"
paso() {
  printf '\n%s▶ %s%s\n' "$C_AMA" "$1" "$C_0"
}

# nota "texto": aclaracion en gris.
nota() {
  printf '  %s%s%s\n' "$C_DIM" "$1" "$C_0"
}

# certificado RUTA: deja en CERT los argumentos de curl para presentar ese certificado de cliente
# (p. ej. servicios/cuentas-service). Arreglo y no texto: la ruta del proyecto tiene espacios.
certificado() {
  CERT=(--cert "$CERTS/$1.crt" --key "$CERTS/$1.key")
}

# dato_visible CLAVE=VALOR: el parametro de formulario como se muestra en pantalla (clave oculta, tokens recortados).
dato_visible() {
  local nombre="${1%%=*}" valor="${1#*=}"
  case "$nombre" in
    password) printf '%s=••••' "$nombre" ;;
    code|code_verifier|refresh_token|_csrf|token) printf '%s=%s…' "$nombre" "${valor:0:12}" ;;
    *) printf '%s' "$1" ;;
  esac
}

# llamar METODO URL [argumentos de curl...]
# SIN_CUERPO=1 no imprime el cuerpo (avisa que lo omite); SIN_CUERPO=2 no imprime ni el aviso.
# Deja el status en ESTADO, el cuerpo en CUERPO, los encabezados en CABECERAS y la version HTTP en VERSION_HTTP.
# Un fallo de TLS (certificado rechazado, protocolo no permitido) deja ESTADO=000 y curl explica el motivo.
llamar() {
  local metodo="$1" url="$2"
  shift 2
  local cabeceras cuerpo resultado resto
  cabeceras=$(mktemp)
  cuerpo=$(mktemp)
  printf '%s%s %s%s\n' "$C_B" "$metodo" "$url" "$C_0"
  local previo=""
  for arg in "$@"; do
    case "$previo" in
      -H)
        case "$arg" in
          Authorization:\ Bearer*) printf '  %s%s…%s\n' "$C_DIM" "${arg:0:40}" "$C_0" ;;
          Content-Type:*) ;;
          *) printf '  %s%s%s\n' "$C_DIM" "$arg" "$C_0" ;;
        esac ;;
      --data-raw) printf '  %s%s%s\n' "$C_DIM" "$arg" "$C_0" ;;
      -d|--data-urlencode) printf '  %s%s%s\n' "$C_DIM" "$(dato_visible "$arg")" "$C_0" ;;
      -u) printf '  %sBasic %s:••••%s\n' "$C_DIM" "${arg%%:*}" "$C_0" ;;
      --cert) printf '  %scertificado de cliente (mTLS): %s%s\n' "$C_DIM" "${arg#"$CERTS"/}" "$C_0" ;;
    esac
    previo="$arg"
  done
  resultado=$(curl -sS -o "$cuerpo" -D "$cabeceras" -w '%{http_code} %{size_download} %{http_version}' -X "$metodo" \
    "${TLS[@]}" "$@" "$url" 2> "$cuerpo.err" || echo "000 0 -")
  ESTADO="${resultado%% *}"
  resto="${resultado#* }"
  VERSION_HTTP="${resto##* }"
  CUERPO=$(cat "$cuerpo")
  CABECERAS=$(cat "$cabeceras")
  local color="$C_OK"
  case "$ESTADO" in 4*|5*|000) color="$C_ERR" ;; esac
  if [ "$ESTADO" = "000" ]; then
    printf '%sSin respuesta HTTP: %s%s\n' "$color" "$(tr -d '\r' < "$cuerpo.err" | head -1)" "$C_0"
  else
    printf '%sHTTP/%s %s%s\n' "$color" "$VERSION_HTTP" "$ESTADO" "$C_0"
  fi
  grep -iE '^(location|idempotency-replayed|www-authenticate):' "$cabeceras" | tr -d '\r' | sed 's/^/  /' || true
  if [ -s "$cuerpo" ] && [ -z "${SIN_CUERPO:-}" ]; then
    python3 -m json.tool --no-ensure-ascii < "$cuerpo" 2>/dev/null || head -c 400 "$cuerpo"
    printf '\n'
  elif [ -s "$cuerpo" ] && [ "$SIN_CUERPO" != "2" ]; then
    printf '  %s(cuerpo omitido en pantalla)%s\n' "$C_DIM" "$C_0"
  fi
  rm -f "$cabeceras" "$cuerpo" "$cuerpo.err"
}

# llamar_interno METODO URL [argumentos de curl...]: como llamar, pero desde un contenedor de curl conectado a la red
# del compose, con el volumen de certificados (rutas /certificados/...). Para lo que no se publica fuera (los microservicios).
llamar_interno() {
  local metodo="$1" url="$2"
  shift 2
  local salida
  printf '%s%s %s%s %s(desde la red interna del compose)%s\n' "$C_B" "$metodo" "$url" "$C_0" "$C_DIM" "$C_0"
  local previo=""
  for arg in "$@"; do
    case "$previo" in
      -H) case "$arg" in Authorization:\ Bearer*) printf '  %s%s…%s\n' "$C_DIM" "${arg:0:40}" "$C_0" ;; *) printf '  %s%s%s\n' "$C_DIM" "$arg" "$C_0" ;; esac ;;
      -u) printf '  %sBasic %s:••••%s\n' "$C_DIM" "${arg%%:*}" "$C_0" ;;
      --cert) printf '  %scertificado de cliente (mTLS): %s%s\n' "$C_DIM" "${arg#/certificados/}" "$C_0" ;;
    esac
    previo="$arg"
  done
  salida=$(docker run --rm --network "$RED" --user 10001 -v "$VOLUMEN_CERTIFICADOS:/certificados:ro" "$IMAGEN_CURL" \
    -sS -i -w '\n@@ESTADO %{http_code} %{http_version}' -X "$metodo" --cacert /certificados/ca/ca.crt "$@" "$url" 2>&1)
  ESTADO=$(printf '%s' "$salida" | sed -n 's/^@@ESTADO \([0-9]*\) .*/\1/p' | tail -1)
  VERSION_HTTP=$(printf '%s' "$salida" | sed -n 's/^@@ESTADO [0-9]* \(.*\)/\1/p' | tail -1)
  CABECERAS=$(printf '%s' "$salida" | sed '/^@@ESTADO/d' | awk 'BEGIN{c=1} /^\r?$/{if(c){c=0; next}} c')
  CUERPO=$(printf '%s' "$salida" | sed '/^@@ESTADO/d' | awk 'BEGIN{c=1} /^\r?$/{if(c){c=0; next}} !c')
  [ -n "$ESTADO" ] || ESTADO=000
  local color="$C_OK"
  case "$ESTADO" in 4*|5*|000) color="$C_ERR" ;; esac
  if [ "$ESTADO" = "000" ]; then
    printf '%sSin respuesta HTTP: %s%s\n' "$color" "$(printf '%s' "$salida" | grep -m1 'curl:')" "$C_0"
  else
    printf '%sHTTP/%s %s%s\n' "$color" "$VERSION_HTTP" "$ESTADO" "$C_0"
  fi
  if [ -n "$CUERPO" ] && [ -z "${SIN_CUERPO:-}" ]; then
    printf '%s' "$CUERPO" | python3 -m json.tool --no-ensure-ascii 2>/dev/null || printf '%s\n' "${CUERPO:0:400}"
  fi
}

# esperar STATUS [CODIGO]: compara el ultimo status (y el campo codigo si se indica).
esperar() {
  local esperado="$1" codigo="${2:-}"
  local recibido_codigo=""
  if [ -n "$codigo" ]; then recibido_codigo=$(campo codigo); fi
  if [ "$ESTADO" = "$esperado" ] && { [ -z "$codigo" ] || [ "$recibido_codigo" = "$codigo" ]; }; then
    printf '%s✔ esperado %s %s%s\n' "$C_OK" "$esperado" "$codigo" "$C_0"
    OK=$((OK + 1))
  else
    printf '%s✘ esperado %s %s, recibido %s %s%s\n' "$C_ERR" "$esperado" "$codigo" "$ESTADO" "$recibido_codigo" "$C_0"
    FALLAS=$((FALLAS + 1))
  fi
}

# comprobar "que se verifica" OBTENIDO ESPERADO
comprobar() {
  if [ "$2" = "$3" ]; then
    printf '%s✔ %s: %s%s\n' "$C_OK" "$1" "$2" "$C_0"
    OK=$((OK + 1))
  else
    printf '%s✘ %s: obtenido "%s", esperado "%s"%s\n' "$C_ERR" "$1" "$2" "$3" "$C_0"
    FALLAS=$((FALLAS + 1))
  fi
}

# encabezado NOMBRE: valor de un encabezado de la ultima respuesta (vacio si no vino).
encabezado() {
  printf '%s\n' "$CABECERAS" | tr -d '\r' | awk -v n="$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')" \
    'BEGIN{FS=": "} tolower($1)==n {sub(/^[^:]*: /, ""); print; exit}'
}

# campo RUTA [JSON]: lee un campo del ultimo cuerpo JSON (o del JSON indicado) con ruta a.b.0.c. Vacio si no existe.
campo() {
  python3 - "$1" "${2:-$CUERPO}" <<'PY'
import json, sys
ruta, texto = sys.argv[1], sys.argv[2]
try:
    valor = json.loads(texto)
    for parte in ruta.split('.'):
        valor = valor[int(parte)] if isinstance(valor, list) else valor[parte]
    print(valor if not isinstance(valor, (dict, list)) else json.dumps(valor, ensure_ascii=False))
except Exception:
    print('')
PY
}

uuid() {
  python3 -c 'import uuid; print(uuid.uuid4())'
}



# claims_jwt TOKEN: encabezado y claims de un JWT, en dos lineas (la firma no se muestra).
claims_jwt() {
  python3 - "$1" <<'PY'
import base64, json, sys
partes = sys.argv[1].split(".")
dec = lambda s: json.loads(base64.urlsafe_b64decode(s + "=" * (-len(s) % 4)))
print("  encabezado: " + json.dumps(dec(partes[0]), ensure_ascii=False))
claims = dec(partes[1])
for k in ("iat", "nbf", "jti", "sid", "auth_time", "azp", "at_hash"):
    claims.pop(k, None)
print("  claims:     " + json.dumps(claims, ensure_ascii=False))
PY
}




# sql "consulta": ejecuta en la base del compose (todos los esquemas) y devuelve filas sin formato (columnas separadas por |).
sql() {
  "${COMPOSE[@]}" exec -T db psql -U banco -d banco_xyz -tAc "$1"
}

# tabla_sql "consulta": igual que sql, con encabezados y columnas alineadas para la captura.
tabla_sql() {
  "${COMPOSE[@]}" exec -T db psql -U banco -d banco_xyz -P footer=off -c "$1" | sed 's/^/  /'
}


# Los circuitos se leen del puerto de operacion (management, HTTP sin TLS) de cada servicio, desde la red interna.
# DESTINO es servicio:puerto del compose (p. ej. cuentas-service:9100) o el nombre de un contenedor (instancia exacta).

# circuito DESTINO NOMBRE: estado del circuit breaker de Resilience4j (CLOSED, OPEN, HALF_OPEN).
circuito() {
  operacion "$1" /actuator/circuitbreakers \
    | python3 -c "import sys,json; print(json.load(sys.stdin)['circuitBreakers']['$2']['state'])" 2>/dev/null
}

# mostrar_circuito DESTINO NOMBRE [ETIQUETA]: estado y metricas del circuito, en una linea.
mostrar_circuito() {
  operacion "$1" /actuator/circuitbreakers | python3 -c "
import sys, json
c = json.load(sys.stdin)['circuitBreakers']['$2']
print('  %-22s circuito %-8s estado=%-9s tasa_de_fallas=%-6s fallidas=%s no_permitidas=%s' % ('${3:-$1}', '$2', c['state'],
      c['failureRate'], c['failedCalls'], c['notPermittedCalls']))"
}

# eventos_circuito DESTINO NOMBRE [N]: ultimos N eventos del circuito (actuator/circuitbreakerevents).
eventos_circuito() {
  operacion "$1" "/actuator/circuitbreakerevents/$2" | python3 -c "
import sys, json
eventos = json.load(sys.stdin)['circuitBreakerEvents'][-${3:-8}:]
for e in eventos:
    detalle = e.get('stateTransition') or (e.get('errorMessage') or '')
    print('  %s %-22s %s' % (e['creationTime'][11:23], e['type'], detalle[:88]))"
}

# kafka_topicos ARGUMENTOS: kafka-topics.sh dentro del contenedor de Kafka.
kafka_topicos() {
  "${KAFKA_CLI[@]}" /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 "$@"
}

# grupo_consumidor GRUPO...: por cada particion, que consumidor la tiene, hasta donde leyo (OFFSET), cuantos mensajes
# tiene el topico (FIN) y cuantos le faltan (LAG). Resume la salida de kafka-consumer-groups.sh --describe.
grupo_consumidor() {
  local grupo
  for grupo in "$@"; do
    "${KAFKA_CLI[@]}" /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 \
      --describe --group "$grupo" 2>/dev/null
  done | python3 -c '
import sys
filas = []
for linea in sys.stdin:
    c = linea.split()
    if len(c) >= 9 and c[0] != "GROUP" and c[2].isdigit():
        cliente = c[8] if c[8] != "-" else "(sin consumidor)"
        filas.append((c[0], c[1], int(c[2]), c[3], c[4], c[5], cliente.replace("consumer-", "")))
filas.sort()
print("  %-22s %-27s %-4s %-6s %-5s %-4s %s" % ("GRUPO", "TOPICO", "PART", "OFFSET", "FIN", "LAG", "CONSUMIDOR"))
for f in filas:
    print("  %-22s %-27s %-4s %-6s %-5s %-4s %s" % f)'
}

# publicar_crudo TOPICO CLAVE MENSAJE: escribe un mensaje tal cual en un topico (para simular duplicados o basura).
publicar_crudo() {
  printf '%s|%s\n' "$2" "$3" | "${KAFKA_CLI_ENTRADA[@]}" /opt/kafka/bin/kafka-console-producer.sh \
    --bootstrap-server kafka:9092 --topic "$1" --property parse.key=true --property key.separator='|' 2>/dev/null
}

# leer_topico TOPICO [SEGUNDOS]: todos los mensajes del topico desde el principio, con particion, offset y clave.
leer_topico() {
  "${KAFKA_CLI[@]}" /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:9092 \
    --topic "$1" --from-beginning --timeout-ms "$(( ${2:-4} * 1000 ))" --property print.partition=true \
    --property print.offset=true --property print.key=true 2>/dev/null
}


resumen_final() {
  printf '\n%s── Resultado: %s verificaciones correctas, %s fallas ──%s\n' "$C_B" "$OK" "$FALLAS" "$C_0"
  [ "$FALLAS" -eq 0 ]
}

# ----------------------------------------------------------------------------------------------- credenciales por canal

# sesion_web USUARIO CLAVE: inicia sesion en bff-web como lo haria el navegador (authorization_code + PKCE contra
# banco-auth). Deja la cookie de sesion en JAR_WEB; el script nunca ve un token.
sesion_web() {
  local usuario="$1" clave="$2" destino csrf
  JAR_WEB=$(mktemp)
  destino=$(curl -s -o /dev/null -w '%{redirect_url}' -c "$JAR_WEB" -b "$JAR_WEB" "${TLS[@]}" "$WEB/oauth2/authorization/banco-auth")
  curl -s -o /dev/null -c "$JAR_WEB" -b "$JAR_WEB" "${TLS[@]}" -H "Accept: text/html" "$destino"
  csrf=$(curl -s -c "$JAR_WEB" -b "$JAR_WEB" "${TLS[@]}" "$AUTH/login" | sed -n 's/.*name="_csrf" value="\([^"]*\)".*/\1/p')
  destino=$(curl -s -o /dev/null -w '%{redirect_url}' -c "$JAR_WEB" -b "$JAR_WEB" "${TLS[@]}" "$AUTH/login" \
    --data-urlencode "username=$usuario" --data-urlencode "password=$clave" --data-urlencode "_csrf=$csrf")
  destino=$(curl -s -o /dev/null -w '%{redirect_url}' -c "$JAR_WEB" -b "$JAR_WEB" "${TLS[@]}" "$destino")
  curl -s -o /dev/null -c "$JAR_WEB" -b "$JAR_WEB" "${TLS[@]}" "$destino"
  curl -s -o /dev/null -c "$JAR_WEB" -b "$JAR_WEB" "${TLS[@]}" "$WEB/web/api/sesion"
  XSRF=$(awk '$6=="XSRF-TOKEN"{print $7}' "$JAR_WEB" | tail -1)
  SESION_WEB=(-b "$JAR_WEB" -c "$JAR_WEB" -H "X-XSRF-TOKEN: $XSRF")
}

# token_movil USUARIO [SCOPES] [CLAVE]: la app movil (cliente PUBLICO banca-movil) obtiene su token con PKCE, sin secreto.
token_movil() {
  local usuario="$1" scopes="${2:-openid cuentas.leer clientes.leer pagos.escribir pagos.leer}" clave_usuario="${3:-$CLAVE_CLIENTES}"
  local jar verificador desafio csrf destino codigo respuesta
  jar=$(mktemp)
  verificador=$(python3 -c 'import secrets,base64;print(base64.urlsafe_b64encode(secrets.token_bytes(32)).rstrip(b"=").decode())')
  desafio=$(python3 -c 'import hashlib,base64,sys;print(base64.urlsafe_b64encode(hashlib.sha256(sys.argv[1].encode()).digest()).rstrip(b"=").decode())' "$verificador")
  curl -s -o /dev/null -c "$jar" -b "$jar" "${TLS[@]}" -G "$AUTH/oauth2/authorize" -H "Accept: text/html" \
    --data-urlencode "response_type=code" --data-urlencode "client_id=banca-movil" --data-urlencode "redirect_uri=$REDIRECCION_MOVIL" \
    --data-urlencode "scope=$scopes" --data-urlencode "state=app-$RANDOM" \
    --data-urlencode "code_challenge=$desafio" --data-urlencode "code_challenge_method=S256"
  csrf=$(curl -s -c "$jar" -b "$jar" "${TLS[@]}" "$AUTH/login" | sed -n 's/.*name="_csrf" value="\([^"]*\)".*/\1/p')
  destino=$(curl -s -o /dev/null -w '%{redirect_url}' -c "$jar" -b "$jar" "${TLS[@]}" "$AUTH/login" \
    --data-urlencode "username=$usuario" --data-urlencode "password=$clave_usuario" --data-urlencode "_csrf=$csrf")
  destino=$(curl -s -o /dev/null -w '%{redirect_url}' -c "$jar" -b "$jar" "${TLS[@]}" "$destino")
  codigo=$(python3 -c 'import sys,urllib.parse as u;print((u.parse_qs(u.urlparse(sys.argv[1]).query).get("code") or [""])[0])' "$destino")
  ERROR_MOVIL=$(python3 -c 'import sys,urllib.parse as u;print((u.parse_qs(u.urlparse(sys.argv[1]).query).get("error") or [""])[0])' "$destino")
  rm -f "$jar"
  respuesta=$(curl -s "${TLS[@]}" "$AUTH/oauth2/token" -d grant_type=authorization_code -d client_id=banca-movil \
    --data-urlencode "code=$codigo" --data-urlencode "redirect_uri=$REDIRECCION_MOVIL" --data-urlencode "code_verifier=$verificador")
  TOKEN=$(campo access_token "$respuesta")
  REFRESCO=$(campo refresh_token "$respuesta")
  BEARER=(-H "Authorization: Bearer $TOKEN")
  [ -n "$TOKEN" ]
}

# token_servicio CLIENTE SECRETO SCOPE: client_credentials de un servicio (bff-cajero, pagos-service...).
token_servicio() {
  local respuesta
  respuesta=$(curl -s "${TLS[@]}" -u "$1:$2" "$AUTH/oauth2/token" -d grant_type=client_credentials --data-urlencode "scope=$3")
  TOKEN_SERVICIO=$(campo access_token "$respuesta")
}

# ---------------------------------------------------------------------------------------------------------- consultas

saldo() {
  sql "select saldo_disponible from cuentas.cuenta where id = $1"
}

# esperar_pago ID [SEGUNDOS]: consulta el pago por bff-web hasta que llegue a un estado final.
esperar_pago() {
  local id="$1" limite="${2:-20}" respuesta=""
  ESTADO_PAGO=""
  for _ in $(seq 1 $((limite * 2))); do
    respuesta=$(curl -s "${TLS[@]}" "${SESION_WEB[@]}" "$WEB/web/api/pagos/$id")
    ESTADO_PAGO=$(campo estado "$respuesta")
    case "$ESTADO_PAGO" in COMPLETADO|RECHAZADO) break ;; esac
    sleep 0.5
  done
  CUERPO="$respuesta"
}

# pago_breve: una linea con el pago y su historial (del ultimo CUERPO).
pago_breve() {
  python3 - "$CUERPO" <<'PY'
import json, sys
try:
    p = json.loads(sys.argv[1], parse_float=str)
except ValueError:
    sys.exit(0)
print("  pago %s · %s · estado %s%s · validacion %s · $%s" % (p.get("id", "")[:8], p.get("operacion"), p.get("estado"),
      " (" + p["motivo"] + ")" if p.get("motivo") else "", p.get("validacion"), p.get("monto")))
for i, h in enumerate(p.get("historial", [])):
    print("  %s %s %-22s %s" % ("historial:" if i == 0 else "          ", h["ocurridoEn"][11:23], h["evento"], h.get("detalle") or ""))
PY
}

# eventos_de_pago ID: todos los eventos de Kafka (en los topicos de la saga) que mencionan ese pago, en orden.
eventos_de_pago() {
  local id="$1" dir topico
  dir=$(mktemp -d)
  for topico in pagos.solicitados cuentas.movimientos seguridad.alertas; do
    leer_topico "$topico" > "$dir/$topico" &
  done
  wait
  python3 - "$id" "$dir" <<'PY'
import json, os, re, sys
buscado, carpeta = sys.argv[1], sys.argv[2]
filas = []
for topico in os.listdir(carpeta):
    for linea in open(os.path.join(carpeta, topico), encoding="utf-8"):
        m = re.match(r"Partition:(\d+)\tOffset:(\d+)\t(\S*)\t(.*)$", linea.rstrip("\n"))
        if not m:
            continue
        try:
            e = json.loads(m[4], parse_float=str)
        except ValueError:
            continue
        if buscado in (e.get("pagoId"), e.get("referencia")):
            filas.append((e.get("ocurridoEn", ""), topico, int(m[1]), int(m[2]), m[3], e))
filas.sort(key=lambda f: (f[0], f[3]))
print("  %-22s %-20s %-4s %-6s %-6s %s" % ("EVENTO", "TOPICO", "PART", "OFFSET", "CLAVE", "DATOS"))
for _, topico, particion, offset, clave, e in filas:
    datos = ["%s=%s" % (c, e[c]) for c in ("operacion", "monto", "motivo", "saldoOrigen", "saldoDestino", "detalle") if e.get(c) is not None]
    print("  %-22s %-20s %-4s %-6s %-6s %s" % (e.get("tipo"), topico, particion, offset, clave, " ".join(datos)[:90]))
print("  (%d eventos del pago %s)" % (len(filas), buscado[:8]))
PY
  rm -rf "$dir"
}

# operacion SERVICIO_DEL_COMPOSE RUTA: consulta el puerto de operacion de un servicio desde la red interna.
operacion() {
  docker run --rm --network "$RED" "$IMAGEN_CURL" -s "http://$1$2"
}

# pan_de_cuenta CUENTA: numero de tarjeta de demostracion de esa cuenta (4 + cuenta en 14 digitos + digito Luhn).
pan_de_cuenta() {
  python3 - "$1" <<'PY'
import sys
base = "4" + sys.argv[1].zfill(14)
suma = 0
for i, d in enumerate(reversed(base)):
    n = int(d) * (2 if i % 2 == 0 else 1)
    suma += n - 9 if n > 9 else n
print(base + str((10 - suma % 10) % 10))
PY
}

# cuenta_ahorro_de USUARIO: primera cuenta de ahorro activa (con tarjeta) del cliente de ese usuario.
cuenta_ahorro_de() {
  sql "select c.id from cuentas.cuenta c join clientes.usuario u on u.cliente_id = c.cliente_id
       where u.usuario = '$1' and c.tipo = 'ahorro' and c.estado = 'ACTIVA'
         and exists (select 1 from cuentas.tarjeta t where t.cuenta_id = c.id) order by c.id limit 1"
}

# bytes_de URL [argumentos de curl...]: tamano del cuerpo que viaja por la red (con gzip si el servidor comprime).
bytes_de() {
  local url="$1"
  shift
  curl -s -o /dev/null -w '%{size_download}' "${TLS[@]}" "$@" "$url"
}

# esperar_sano SERVICIO [SEGUNDOS]: espera a que todas las instancias del servicio esten healthy.
esperar_sano() {
  local servicio="$1" limite="${2:-180}" inicio=$SECONDS c pendientes
  while [ $((SECONDS - inicio)) -lt "$limite" ]; do
    pendientes=0
    for c in $("${COMPOSE[@]}" ps -q "$servicio"); do
      [ "$(docker inspect --format '{{.State.Health.Status}}' "$c" 2>/dev/null)" = "healthy" ] || pendientes=1
    done
    if [ "$pendientes" -eq 0 ] && [ -n "$("${COMPOSE[@]}" ps -q "$servicio")" ]; then
      nota "$servicio sano en $((SECONDS - inicio)) s"
      return 0
    fi
    sleep 3
  done
  nota "$servicio no quedo sano en $limite s"
  return 1
}

# registros SERVICIO PATRON [N]: ultimas N lineas del log del servicio que contienen el patron.
registros() {
  "${COMPOSE[@]}" logs --no-color --no-log-prefix --since 5m "$1" 2>/dev/null | grep -E "$2" | tail -"${3:-3}" | cut -c1-180 | sed 's/^/  /'
}

# sql_pruebas "consulta": como sql, en la base de pruebas del batch (banco_xyz_pruebas).
sql_pruebas() {
  "${COMPOSE[@]}" exec -T db psql -U banco -d banco_xyz_pruebas -tAc "$1"
}

# asegurar_saldo CUENTA MINIMO: con la sesion web abierta (SESION_WEB), deposita por la saga lo que falte para que la
# cuenta tenga al menos MINIMO. Los saldos del legacy son chicos: asi los scripts se pueden repetir sin agotarlos.
asegurar_saldo() {
  local actual falta respuesta
  actual=$(saldo "$1")
  falta=$(python3 -c "print(max(0, int($2 - $actual)))")
  [ "$falta" -gt 0 ] || return 0
  respuesta=$(curl -s "${TLS[@]}" "${SESION_WEB[@]}" "${JSON[@]}" -H "Idempotency-Key: $(uuid)" \
    "$WEB/web/api/pagos/depositos" --data-raw "{\"cuentaDestino\":$1,\"monto\":$falta}")
  esperar_pago "$(campo id "$respuesta")" 20
  nota "deposito previo de \$$falta en la cuenta $1 para repetir la prueba ($ESTADO_PAGO)"
}
