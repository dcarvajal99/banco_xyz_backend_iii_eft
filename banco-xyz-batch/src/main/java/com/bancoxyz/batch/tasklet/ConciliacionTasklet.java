package com.bancoxyz.batch.tasklet;

import com.bancoxyz.common.Constantes;
import com.bancoxyz.entity.Conciliacion;
import com.bancoxyz.repository.ConciliacionRepository;
import com.bancoxyz.repository.CuentaInteresRepository;
import com.bancoxyz.repository.MovimientoAnualRepository;
import com.bancoxyz.repository.RegistroRechazadoRepository;
import com.bancoxyz.repository.TransaccionRepository;
import com.bancoxyz.util.EscritorCsv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.LongUnaryOperator;
import java.util.stream.Stream;

/**
 * Conciliacion de totales de control: cada linea del archivo legacy tiene que estar en algun lado.
 *
 * <p>Va despues de migrar y de exportar la bitacora, y antes del decisor de calidad. Para cada Step que
 * leyo un archivo comprueba cuatro igualdades, todas con numeros que salen de fuentes distintas:</p>
 * <ol>
 *   <li><b>lineas del archivo = lineas leidas</b>: el archivo se cuenta de nuevo aqui, sin pasar por el
 *       lector; detecta un lector que se salto un tramo (por ejemplo, una particion mal calculada).</li>
 *   <li><b>leidas = migradas + filtradas + omitidas</b>: ninguna fila se pierde dentro del Step.</li>
 *   <li><b>migradas = filas en la tabla destino</b>: lo que Spring Batch dice que escribio esta de verdad
 *       en la base, ni una fila de mas (un reintento que duplico) ni de menos.</li>
 *   <li><b>filtradas + omitidas = filas en la bitacora</b>: cada rechazo quedo registrado con su linea y su
 *       motivo, que es lo que permite corregir el origen.</li>
 * </ol>
 *
 * <p>Si alguna no se cumple, la corrida falla y no se publica: es un problema de integridad, no de calidad
 * del dato, y queda marcada para revision manual (el lanzador no la reejecuta solo). El resultado queda en
 * la tabla {@code conciliacion} y en {@code conciliacion.csv}, sea cual sea.</p>
 */
@Component
public class ConciliacionTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(ConciliacionTasklet.class);

    /** Archivo y tabla destino de cada Step que lee del legacy. */
    private record Destino(String archivo, LongUnaryOperator filasEnTabla) {
    }

    private final Map<String, Destino> destinos;
    private final RegistroRechazadoRepository rechazados;
    private final ConciliacionRepository conciliaciones;

    public ConciliacionTasklet(TransaccionRepository transacciones, CuentaInteresRepository cuentas,
                               MovimientoAnualRepository movimientos, RegistroRechazadoRepository rechazados,
                               ConciliacionRepository conciliaciones) {
        this.destinos = Map.of(
                Constantes.STEP_PROCESAR_TRANSACCIONES,
                new Destino(Constantes.ARCHIVO_TRANSACCIONES, transacciones::countByJobExecutionId),
                Constantes.STEP_PROCESAR_INTERESES,
                new Destino(Constantes.ARCHIVO_INTERESES, cuentas::countByJobExecutionId),
                Constantes.STEP_PROCESAR_MOVIMIENTOS,
                new Destino(Constantes.ARCHIVO_CUENTAS_ANUALES, movimientos::countByJobExecutionId));
        this.rechazados = rechazados;
        this.conciliaciones = conciliaciones;
    }

    @Override
    public RepeatStatus execute(StepContribution contribucion, ChunkContext contexto) throws Exception {
        JobExecution job = contexto.getStepContext().getStepExecution().getJobExecution();
        Long jobId = job.getId();
        Path entrada = Path.of(String.valueOf(contexto.getStepContext().getJobParameters().get(Constantes.PARAM_ENTRADA)));
        Path salida = Path.of(String.valueOf(contexto.getStepContext().getJobParameters().get(Constantes.PARAM_SALIDA)));

        List<Conciliacion> resultados = new ArrayList<>();
        for (StepExecution paso : job.getStepExecutions()) {
            // Mismo criterio que el decisor: solo el Step que representa al archivo completo (el gestor
            // si hay particiones), porque sus contadores ya traen sumados los de cada particion.
            if (paso.getStepName().contains(Constantes.SUFIJO_PARTICION)
                    || !paso.getExecutionContext().containsKey(Constantes.CTX_PASO_DE_MIGRACION)) {
                continue;
            }
            Destino destino = destinos.get(paso.getStepName());
            if (destino == null) {
                continue;
            }
            resultados.add(conciliar(jobId, paso, destino, entrada.resolve(destino.archivo())));
        }
        conciliaciones.saveAll(resultados);
        escribirCsv(salida.resolve(Constantes.SALIDA_CONCILIACION), resultados);

        List<Conciliacion> descuadradas = resultados.stream().filter(c -> !c.isCuadra()).toList();
        resultados.forEach(c -> log.info("Conciliacion {} | lineas={} leidas={} migradas={} filtradas={} omitidas={} "
                        + "enTabla={} enBitacora={} -> {}", c.getArchivo(), c.getLineasArchivo(), c.getLeidas(),
                c.getMigradas(), c.getFiltradas(), c.getOmitidas(), c.getFilasEnTabla(), c.getFilasEnBitacora(),
                c.isCuadra() ? "CUADRA" : "NO CUADRA: " + c.getDiferencias()));
        if (!descuadradas.isEmpty()) {
            job.getExecutionContext().putString(Constantes.CTX_REVISION_MANUAL,
                    "La conciliacion no cuadra en " + descuadradas.stream().map(Conciliacion::getArchivo).toList());
            throw new IllegalStateException("Conciliacion de totales de control: no cuadra en "
                    + descuadradas.stream().map(c -> c.getArchivo() + " (" + c.getDiferencias() + ")").toList()
                    + ". La corrida no se publica.");
        }
        contribucion.incrementWriteCount(resultados.size());
        return RepeatStatus.FINISHED;
    }

    private Conciliacion conciliar(Long jobId, StepExecution paso, Destino destino, Path archivo) {
        long lineas = contarLineasDeDatos(archivo);
        long leidas = paso.getReadCount() + paso.getReadSkipCount();
        long migradas = paso.getWriteCount();
        long filtradas = paso.getFilterCount();
        long omitidas = paso.getSkipCount();
        long enTabla = destino.filasEnTabla().applyAsLong(jobId);
        long enBitacora = rechazados.countByJobExecutionIdAndArchivo(jobId, destino.archivo());

        List<String> diferencias = new ArrayList<>();
        if (lineas != leidas) {
            diferencias.add("el archivo trae " + lineas + " lineas y se leyeron " + leidas);
        }
        if (leidas != migradas + filtradas + omitidas) {
            diferencias.add("leidas " + leidas + " <> migradas + filtradas + omitidas " + (migradas + filtradas + omitidas));
        }
        if (enTabla != migradas) {
            diferencias.add("la tabla tiene " + enTabla + " filas y se migraron " + migradas);
        }
        if (enBitacora != filtradas + omitidas) {
            diferencias.add("la bitacora tiene " + enBitacora + " rechazos y hubo " + (filtradas + omitidas));
        }
        return new Conciliacion(jobId, paso.getStepName(), destino.archivo(), lineas, leidas, migradas, filtradas,
                omitidas, enTabla, enBitacora, diferencias.isEmpty() ? null : String.join("; ", diferencias));
    }

    /** Lineas sin la cabecera, contadas igual que el particionador (cada linea es una fila para el lector). */
    static long contarLineasDeDatos(Path archivo) {
        try (Stream<String> lineas = Files.lines(archivo, StandardCharsets.UTF_8)) {
            return Math.max(0, lineas.count() - 1);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer el archivo para conciliarlo: " + archivo, e);
        }
    }

    private static void escribirCsv(Path destino, List<Conciliacion> resultados) throws IOException {
        List<String> filas = resultados.stream().map(c -> EscritorCsv.fila(c.getArchivo(), c.getLineasArchivo(),
                c.getLeidas(), c.getMigradas(), c.getFiltradas(), c.getOmitidas(), c.getFilasEnTabla(),
                c.getFilasEnBitacora(), c.isCuadra() ? "SI" : "NO",
                c.getDiferencias() == null ? "" : c.getDiferencias())).toList();
        EscritorCsv.escribir(destino,
                "archivo,lineas_archivo,leidas,migradas,filtradas,omitidas,filas_en_tabla,filas_en_bitacora,cuadra,diferencias",
                filas);
    }
}
