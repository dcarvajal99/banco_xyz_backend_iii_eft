#!/usr/bin/env bash
# Corre las evidencias en el orden del informe y guarda cada salida, con sus colores, en
# infra/evidencias/salidas/NN_<script>.txt. Las dos ultimas detienen o escalan servicios y los dejan como estaban.
# Requiere el ecosistema arriba: docker compose up -d --build --wait
# Uso: infra/scripts/probar_todo.sh [script ...]     (sin argumentos corre las ocho)
set -uo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
SALIDAS="$DIR/../evidencias/salidas"
mkdir -p "$SALIDAS"
TODOS=(00:estructura 01:ecosistema 02:batch_migracion 03:bff_canales 04:microservicios 05:saga_pagos 06:resiliencia 07:escalabilidad)
PEDIDOS=("$@")

resultado=0
for par in "${TODOS[@]}"; do
  numero="${par%%:*}" script="${par#*:}"
  if [ ${#PEDIDOS[@]} -gt 0 ] && ! printf '%s\n' "${PEDIDOS[@]}" | grep -qx "${script}\(.sh\)\{0,1\}"; then continue; fi
  bash "$DIR/$script.sh" 2>&1 | tee "$SALIDAS/${numero}_$script.txt"
  [ "${PIPESTATUS[0]}" -eq 0 ] || resultado=1
done
printf '\n'
grep -h "Resultado:" "$SALIDAS"/*.txt | sed $'s/\033\\[[0-9;]*m//g'
exit $resultado
