#!/usr/bin/env bash
# Compila y prueba cada una de las diez aplicaciones por separado (mvn verify: pruebas + cobertura JaCoCo), como lo
# haria su propio pipeline. Las pruebas no necesitan Docker: H2, Kafka embebido y servidores simulados.
# Uso: infra/scripts/verificar_proyectos.sh [LOG]      (MVN_OPCIONES=-o para trabajar sin red)
set -uo pipefail
RAIZ="$(cd "$(dirname "$0")/../.." && pwd)"
LOG="${1:-$RAIZ/infra/evidencias/verify.log}"
mkdir -p "$(dirname "$LOG")"
: > "$LOG"
resultado=0
for p in banco-xyz-batch config-server eureka-server banco-auth clientes-service cuentas-service pagos-service bff-web bff-movil bff-cajero; do
  ( cd "$RAIZ/$p" && rm -rf target/surefire-reports target/site && \
    if [ -x ./mvnw ]; then ./mvnw -B ${MVN_OPCIONES:-} verify; else mvn -B ${MVN_OPCIONES:-} verify; fi ) >> "$LOG" 2>&1
  estado=$?
  printf '%-17s %s\n' "$p" "$([ $estado -eq 0 ] && echo 'BUILD SUCCESS' || echo 'BUILD FAILURE')"
  [ $estado -eq 0 ] || resultado=1
done
exit $resultado
