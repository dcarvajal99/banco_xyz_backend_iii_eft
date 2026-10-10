#!/usr/bin/env bash
# Firma HMAC-SHA256 de una solicitud del terminal de cajero (seccion 11.10 de la especificacion).
#
#   canonica = METODO \n RUTA \n MARCA_TIEMPO \n NONCE \n IDEMPOTENCY_KEY \n sha256hex(cuerpo)
#   X-Firma  = Base64( HMAC-SHA256( secreto en UTF-8, canonica ) )
#
# RUTA incluye "?query" cruda solo si existe. IDEMPOTENCY_KEY vacia si no viene. Sin salto de linea final.
#
# Uso:
#   firmar_cajero.sh METODO RUTA MARCA NONCE CLAVE CUERPO SECRETO
#   firmar_cajero.sh --probar-vectores      verifica los 3 vectores fijos que tambien usa la prueba del BFF
#   source firmar_cajero.sh                 deja disponible la funcion firmar

firmar() {
  python3 - "$@" <<'PY'
import base64, hashlib, hmac, sys
metodo, ruta, marca, nonce, clave, cuerpo, secreto = sys.argv[1:8]
canonica = "\n".join([metodo, ruta, marca, nonce, clave, hashlib.sha256(cuerpo.encode("utf-8")).hexdigest()])
print(base64.b64encode(hmac.new(secreto.encode("utf-8"), canonica.encode("utf-8"), hashlib.sha256).digest()).decode())
PY
}

probar_vectores() {
  local secreto="atm-001-secreto-de-desarrollo-0123456789" fallas=0 obtenida
  local -a vectores=(
    "POST|/cajero/v1/retiros|1767225600|n0nc3-0000000001|3f1c2a7e-9b1d-4c1e-8f00-000000000001|{\"monto\":3000}|W7evtUL9A5D6kipDrpG3JYYPSiVqCvWuNXgXgIpi3qQ="
    "GET|/cajero/v1/saldo|1767225600|n0nc3-0000000002|||GZENspHNwt0pLI/TEjooPZtqhrWi9ml57BqUdgqDLBU="
    "GET|/cajero/v1/saldo?formato=corto|1767225600|n0nc3-0000000003|||q4TLLd+aadV9gsmmg6qJ9sQNWvQThLvzGXB7bzR9JQU="
  )
  for vector in "${vectores[@]}"; do
    IFS='|' read -r metodo ruta marca nonce clave cuerpo esperada <<< "$vector"
    obtenida=$(firmar "$metodo" "$ruta" "$marca" "$nonce" "$clave" "$cuerpo" "$secreto")
    if [ "$obtenida" = "$esperada" ]; then
      echo "OK    $metodo $ruta -> $obtenida"
    else
      echo "FALLA $metodo $ruta -> $obtenida (esperada $esperada)"
      fallas=$((fallas + 1))
    fi
  done
  return "$fallas"
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  case "${1:-}" in
    -h|--help) sed -n '2,15p' "$0" ;;
    --probar-vectores) probar_vectores ;;
    *)
      if [ "$#" -ne 7 ]; then sed -n '2,15p' "$0"; exit 2; fi
      firmar "$@"
      ;;
  esac
fi
