#!/usr/bin/env bash
# Corre todas las evidencias en orden y guarda cada salida (sin colores) en infra/evidencias/<script>.txt.
# Requiere el ecosistema arriba: docker compose up -d --build --wait
# Uso: infra/scripts/probar_todo.sh [script ...]     (sin argumentos corre los siete)
set -uo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
SALIDAS="$DIR/../evidencias"
mkdir -p "$SALIDAS"
SCRIPTS=("$@")
[ ${#SCRIPTS[@]} -gt 0 ] || SCRIPTS=(ecosistema batch_migracion bff_canales microservicios saga_pagos resiliencia escalabilidad)

resultado=0
for s in "${SCRIPTS[@]}"; do
  s="${s%.sh}"
  bash "$DIR/$s.sh" 2>&1 | tee >(sed $'s/\033\\[[0-9;]*m//g' > "$SALIDAS/$s.txt")
  estado=${PIPESTATUS[0]}
  [ "$estado" -eq 0 ] || resultado=1
  printf '%s -> %s\n' "$s" "$([ "$estado" -eq 0 ] && echo OK || echo CON FALLAS)" >> "$SALIDAS/resumen.txt"
done
exit $resultado
