#!/usr/bin/env bash
# Evidencia: la migracion batch del legacy (fin_legacy_data) con Spring Batch.
#   Paralelismo: los tres archivos a la vez y cada uno particionado. Errores: omision y reintento por fila, conciliacion
#   de totales, decisor de calidad (revision manual), reejecucion automatica y reanudacion idempotente.
# Las corridas de demostracion de errores van a una base aparte (banco_xyz_pruebas) para no tocar la del ecosistema.
set -uo pipefail
source "$(dirname "$0")/lib.sh"

titulo "Migracion batch del legacy" "Criterio 2 (batch: errores avanzados, paralelismo, escalabilidad) y requisito de negocio 3"
EJECUCION=$(sql "select max(e.job_execution_id) from batch_job_execution e join batch_job_instance i using (job_instance_id)
                 where i.job_name = 'migracionCompletaJob' and e.status = 'COMPLETED'")

# batch ARGUMENTOS...: corre el contenedor del batch (una ejecucion) contra la base de pruebas y deja el codigo en CODIGO.
batch() {
  "${COMPOSE[@]}" run --rm --no-deps -e SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/banco_xyz_pruebas \
    banco-xyz-batch "$@" --salida=/tmp/salida > "$SALIDA_BATCH" 2>&1
  CODIGO=$?
  grep -E "finalizado con estado|Decisor de calidad|RE-EJECUCION|ya termino|[Rr]eejecu|[Rr]eintent|simulad" "$SALIDA_BATCH" \
    | grep -vE 'DEBUG|\\_|Configuracion efectiva' | tail -6 | cut -c1-170 | sed 's/^/  /'
}
SALIDA_BATCH=$(mktemp)

paso "Carga inicial del compose: contenedor de una ejecucion que termino antes que los microservicios"
batch_id=$("${COMPOSE[@]}" ps -aq banco-xyz-batch | head -1)
comprobar "banco-xyz-batch" "$(docker inspect --format '{{.State.Status}} codigo {{.State.ExitCode}}' "$batch_id")" "exited codigo 0"
"${COMPOSE[@]}" logs --no-color --no-log-prefix banco-xyz-batch 2>/dev/null | grep -E "Resultado|Decisor|Conciliacion|publicad" \
  | tail -5 | cut -c1-170 | sed 's/^/  /'

paso "Paralelismo: tres archivos en flujos paralelos y cada archivo en particiones (ejecucion $EJECUCION)"
tabla_sql "select split_part(step_name, ':', 1) paso, count(*) particiones, sum(read_count) leidas, sum(write_count) escritas,
                  sum(read_skip_count + process_skip_count + write_skip_count) omitidas, sum(filter_count) filtradas,
                  to_char(max(end_time) - min(start_time), 'MI:SS.MS') duracion
           from batch_step_execution where job_execution_id = $EJECUCION and step_name like '%:particion%'
           group by 1 order by 1"
tabla_sql "select step_name paso, status, to_char(start_time, 'HH24:MI:SS.MS') inicio, to_char(end_time, 'HH24:MI:SS.MS') fin
           from batch_step_execution where job_execution_id = $EJECUCION and step_name not like '%:particion%' order by start_time"

paso "Conciliacion de totales de control: lineas = leidas = migradas + filtradas + omitidas, y cuadra con las tablas"
tabla_sql "select archivo, lineas_archivo lineas, leidas, migradas, filtradas, omitidas, filas_en_tabla en_tabla,
                  filas_en_bitacora en_bitacora, cuadra from migracion.conciliacion where job_execution_id = $EJECUCION order by archivo"

paso "Bitacora de rechazos: cada fila sucia queda con su linea, contenido y motivo (nada se pierde en silencio)"
tabla_sql "select archivo, clasificacion, count(*) filas from migracion.registro_rechazado where job_execution_id = $EJECUCION
           group by 1, 2 order by 1, 3 desc"

paso "Lo que publica para los microservicios: maestro de clientes con ids estables y el cierre de cada cuenta"
tabla_sql "select id, nombre, edad from migracion.cliente_migrado order by id"
comprobar "cuentas publicadas en migracion.cierre_cuenta" "$(sql "select count(*) from migracion.cierre_cuenta")" "50"

paso "Equivalencia con el legacy: un programa Python independiente recalcula todo desde los CSV y compara valor a valor"
COPIA=$(mktemp -d)
copiar_volumen banco-xyz_salida-batch "$COPIA"
python3 "$RAIZ_REPO/banco-xyz-batch/scripts/equivalencia_legacy.py" "$RAIZ_REPO/banco-xyz-batch/data/semana_3" "$COPIA" \
  | tail -12 | sed 's/^/  /'
comprobar "diferencias con el legacy (codigo de salida)" "${PIPESTATUS[0]}" "0"
rm -rf "$COPIA"

# ------------------------------------------------------------------------------------------- manejo de errores avanzado
"${COMPOSE[@]}" exec -T db psql -U banco -d banco_xyz -qc "drop database if exists banco_xyz_pruebas" > /dev/null 2>&1
"${COMPOSE[@]}" exec -T db psql -U banco -d banco_xyz -qc "create database banco_xyz_pruebas" > /dev/null
CORRIDA="politica-$(date +%H%M%S)"

paso "Politica por defecto (umbral 30 %): semana_3 trae 40,7 % de filas sucias -> revision manual, nada se publica"
batch --job=completa --dataset=semana_3 --corrida="$CORRIDA"
comprobar "codigo de salida (3 = revision manual)" "$CODIGO" "3"
comprobar "cuentas publicadas" "$(sql_pruebas "select count(*) from migracion.cierre_cuenta")" "0"

paso "El area de datos autoriza la contingencia (umbral 0,80): se retoma la MISMA corrida, sin migrar de nuevo"
batch --job=completa --dataset=semana_3 --corrida="$CORRIDA" --banco.batch.umbral-rechazo-omision=0.80
comprobar "codigo de salida" "$CODIGO" "0"
comprobar "cuentas publicadas" "$(sql_pruebas "select count(*) from migracion.cierre_cuenta")" "50"

paso "Relanzar una corrida ya COMPLETED no migra dos veces"
antes=$(sql_pruebas "select count(*) from migracion.movimiento_anual")
batch --job=completa --dataset=semana_3 --corrida="$CORRIDA" --banco.batch.umbral-rechazo-omision=0.80
comprobar "codigo de salida" "$CODIGO" "0"
comprobar "filas de movimientos sin duplicar" "$(sql_pruebas "select count(*) from migracion.movimiento_anual")" "$antes"

paso "Falla tecnica (la base se cae durante toda la primera ejecucion): el lanzador reejecuta solo y termina bien"
batch --job=intereses --dataset=semana_1 --corrida="critico-$(date +%H%M%S)" --banco.batch.simular-fallo-critico=true
comprobar "codigo de salida tras la reejecucion automatica" "$CODIGO" "0"
sql_pruebas "select i.job_name || ' ' || e.job_execution_id || ' ' || e.status || ' ' || coalesce(e.exit_code, '')
             from batch_job_execution e join batch_job_instance i using (job_instance_id)
             where i.job_key = (select job_key from batch_job_instance order by job_instance_id desc limit 1)
             order by e.job_execution_id" | sed 's/^/  ejecucion: /'

paso "Fallo transitorio de escritura: el chunk se reintenta con espera exponencial y el paso termina COMPLETED"
batch --job=transacciones --dataset=semana_1 --corrida="transitorio-$(date +%H%M%S)" --banco.batch.simular-fallo-transitorio=true
comprobar "codigo de salida" "$CODIGO" "0"
grep -iE "reintento|transitori" "$SALIDA_BATCH" | head -3 | cut -c1-170 | sed 's/^/  /'

rm -f "$SALIDA_BATCH"
resumen_final
