#!/usr/bin/env bash
# Simula el software de un terminal de cajero: abre mTLS con el certificado del terminal, firma cada solicitud y la
# envia al BFF cajero.
# Uso: source "$(dirname "$0")/cajero_lib.sh"
#
# Variables para las pruebas negativas (se aplican a la siguiente llamada y luego se limpian):
#   SIN_FIRMA=1       no envia encabezados de firma
#   MARCA_FORZADA=N   usa esa marca de tiempo
#   NONCE_FORZADO=X   usa ese nonce
#   FIRMA_FORZADA=B64 envia esa firma en vez de la calculada
#   SIN_CERTIFICADO=1 no presenta certificado de cliente
#   CERTIFICADO_FORZADO=RUTA presenta ese certificado (p. ej. terminales/ATM-002 o intruso/ATM-001-falso)
#   CORRELACION=ID    envia ese X-Correlacion-Id (no forma parte de la firma)

DIR_SCRIPTS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$DIR_SCRIPTS/lib.sh"
source "$DIR_SCRIPTS/firmar_cajero.sh"

TERMINAL="${TERMINAL:-ATM-001}"

secreto_de() {
  case "$1" in
    ATM-001) echo "${SECRETO_ATM_001:-atm-001-secreto-de-desarrollo-0123456789}" ;;
    ATM-002) echo "${SECRETO_ATM_002:-atm-002-secreto-de-desarrollo-9876543210}" ;;
    *) echo "secreto-desconocido" ;;
  esac
}

# cajero METODO RUTA [CUERPO] [IDEMPOTENCY_KEY] [TOKEN] [TERMINAL]
cajero() {
  local metodo="$1" ruta="$2" cuerpo="${3:-}" clave="${4:-}" token="${5:-}" terminal="${6:-$TERMINAL}"
  local marca="${MARCA_FORZADA:-$(date +%s)}"
  local nonce="${NONCE_FORZADO:-n-$(python3 -c 'import secrets; print(secrets.token_hex(12))')}"
  local firma="${FIRMA_FORZADA:-$(firmar "$metodo" "$ruta" "$marca" "$nonce" "$clave" "$cuerpo" "$(secreto_de "$terminal")")}"
  local -a args=()
  if [ -z "${SIN_FIRMA:-}" ]; then
    args+=(-H "X-Terminal-Id: $terminal" -H "X-Marca-Tiempo: $marca" -H "X-Nonce: $nonce" -H "X-Firma: $firma")
  fi
  if [ -z "${SIN_CERTIFICADO:-}" ]; then
    certificado "${CERTIFICADO_FORZADO:-terminales/$terminal}"
    args+=("${CERT[@]}")
  fi
  if [ -n "${CORRELACION:-}" ]; then args+=(-H "X-Correlacion-Id: $CORRELACION"); fi
  if [ -n "$clave" ]; then args+=(-H "Idempotency-Key: $clave"); fi
  if [ -n "$token" ]; then args+=(-H "Authorization: Bearer $token"); fi
  if [ -n "$cuerpo" ]; then args+=(-H "Content-Type: application/json" --data-raw "$cuerpo"); fi
  ULTIMO_NONCE="$nonce"
  unset SIN_FIRMA MARCA_FORZADA NONCE_FORZADO FIRMA_FORZADA SIN_CERTIFICADO CERTIFICADO_FORZADO CORRELACION
  llamar "$metodo" "$CAJERO$ruta" ${args[@]+"${args[@]}"}
}
