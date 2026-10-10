#!/usr/bin/env bash
# Evidencia: diez aplicaciones independientes (no un proyecto modular). Cada carpeta es un proyecto Maven completo con
# su pom.xml (sin padre comun), sus pruebas y su Dockerfile; ninguna depende del codigo de otra. Se encuentran por
# Config Server, Eureka y topicos de Kafka, nunca por una ruta a la carpeta vecina.
set -uo pipefail
source "$(dirname "$0")/lib.sh"
cd "$RAIZ_REPO" || exit 1
APPS="bff-web bff-movil bff-cajero clientes-service cuentas-service pagos-service config-server eureka-server banco-auth banco-xyz-batch"

titulo "Diez aplicaciones independientes" "Criterios 1 y 2 (procesos de la migracion y arquitectura propuesta)"

paso "Una carpeta por aplicacion: build, pruebas e imagen propios"
printf '  %-3s %-17s %-6s %-7s %-7s %-10s %-26s %s\n' "N" "APLICACION" "CLASES" "@TEST" "DOCKER" "PUERTO" "POM PADRE" "ROL"
n=0
for p in $APPS; do
  n=$((n + 1))
  clases=$(find "$p/src/main/java" -name '*.java' | wc -l | tr -d ' ')
  pruebas=$(grep -rl "@Test\|@ParameterizedTest" "$p/src/test/java" 2>/dev/null | xargs grep -hE "^\s*@(Test|ParameterizedTest)" 2>/dev/null | wc -l | tr -d ' ')
  padre=$(sed -n '/<parent>/,/<\/parent>/s/.*<artifactId>\(.*\)<\/artifactId>.*/\1/p' "$p/pom.xml")
  puerto=$(sed -n 's/^server.port=//p' "config-server/configuracion/$p.properties" 2>/dev/null)
  [ -n "$puerto" ] || puerto=$(sed -n 's/^server.port=//p' "$p/src/main/resources/application.properties" 2>/dev/null | head -1)
  case "$p" in
    bff-*) rol="BFF del canal ${p#bff-}" ;;
    *-service) rol="microservicio" ;;
    banco-auth) rol="servidor de autorizacion" ;;
    banco-xyz-batch) rol="Spring Batch (3 jobs)"; puerto="-" ;;
    *) rol="plano de control" ;;
  esac
  [ "$p" = eureka-server ] && puerto="8761/8762"
  printf '  %-3s %-17s %-6s %-7s %-7s %-10s %-26s %s\n' "$n" "$p/" "$clases" "$pruebas" \
    "$([ -f "$p/Dockerfile" ] && echo si || echo no)" "$puerto" "$padre" "$rol"
done
comprobar "proyectos con su propio pom.xml y Dockerfile" "$(for p in $APPS; do [ -f "$p/pom.xml" ] && [ -f "$p/Dockerfile" ] && echo x; done | wc -l | tr -d ' ')" "10"

paso "Independientes, no modulares: ningun pom.xml depende de otra aplicacion del banco ni las agrupa como modulos"
cruzadas=$(for p in $APPS; do sed -n '/<dependencies>/,/<\/dependencies>/p' "$p/pom.xml" | grep -c "<groupId>com.bancoxyz</groupId>"; done | paste -sd+ - | bc)
modulos=$(grep -l "<modules>" */pom.xml pom.xml 2>/dev/null | wc -l | tr -d ' ')
comprobar "dependencias entre aplicaciones (com.bancoxyz en <dependencies>)" "$cruzadas" "0"
comprobar "pom.xml agregadores con <modules>" "$modulos" "0"
nota "Cada aplicacion tiene su propio paquete raiz (com.bancoxyz.<app>) y su copia de los contratos que consume (DTO y eventos)."

paso "Como se encuentran sin conocerse: configuracion central, descubrimiento y eventos"
for p in bff-web bff-movil bff-cajero clientes-service cuentas-service pagos-service banco-auth; do
  urls=$(grep -hE "^banco\..*url=https://[a-z-]+/" "config-server/configuracion/$p.properties" | sed 's/.*https:\/\/\([a-z-]*\)\/.*/\1/' | sort -u | paste -sd, - | sed 's/,/, /g')
  topicos=$(find "$p/src/main/java" -name Topicos.java -exec sed -n 's/.*String [A-Z_]* = "\([a-z.]*\)";/\1/p' {} \; 2>/dev/null | grep -v DLT | sort -u | paste -sd, - | sed 's/,/, /g')
  printf '  %-16s Eureka: %-48s Kafka: %s\n' "$p" "${urls:--}" "${topicos:--}"
done

paste_compose=$(awk '/^services:/ {s=1; next} /^[a-z]/ {s=0} s && /^  [a-z][a-z0-9-]*:$/ {sub(":", ""); printf "%s ", $1}' docker-compose.yaml)
paso "docker-compose.yaml: los contenedores en orden de arranque"
printf '%s\n' "$paste_compose" | fold -w 116 -s | sed 's/^/  /'

resumen_final
