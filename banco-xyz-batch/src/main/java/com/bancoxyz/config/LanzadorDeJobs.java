package com.bancoxyz.config;

import com.bancoxyz.common.Constantes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRestartException;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Punto de entrada por linea de comandos de la migracion.
 *
 * <p>Cumple el papel que en el sistema legacy tenia el shell script del operador: recibe
 * que proceso correr y sobre que carpeta, y se lo entrega al {@link JobLauncher}, que es
 * quien crea el contexto de ejecucion del Job.</p>
 *
 * <p>Se lanza explicitamente en vez de dejar que Spring Boot ejecute todos los Jobs al
 * arrancar ({@code spring.batch.job.enabled=false}), porque el banco necesita poder correr
 * el cierre de intereses sin volver a procesar las transacciones del dia.</p>
 *
 * <h2>Uso</h2>
 * <pre>
 *   java -jar banco-xyz-batch.jar --job=transacciones --dataset=semana_3
 *   java -jar banco-xyz-batch.jar --job=intereses    --entrada=data/semana_1
 *   java -jar banco-xyz-batch.jar --job=completa     --salida=salida
 *
 *   # Tuning: los parametros de escalado se sobrescriben sin recompilar
 *   java -jar banco-xyz-batch.jar --job=transacciones --dataset=semana_3 \
 *        --banco.batch.tamano-chunk=50 --banco.batch.hilos-por-paso=1
 *
 *   # Re-ejecucion: dos corridas con la misma etiqueta son la MISMA JobInstance,
 *   # de modo que la segunda reanuda la primera en vez de empezar de cero
 *   java -jar banco-xyz-batch.jar --job=transacciones --dataset=semana_3 --corrida=cierre-agosto
 * </pre>
 *
 * <h2>Politica de reejecucion</h2>
 * <ul>
 *   <li>Fallo tecnico (la base se cayo y se agotaron los reintentos del chunk): el lanzador reejecuta
 *       solo, hasta {@code banco.batch.reejecuciones-automaticas} veces, sobre la misma JobInstance.</li>
 *   <li>Calidad inaceptable o conciliacion que no cuadra: no se reejecuta; codigo de salida 3 (revision
 *       manual).</li>
 *   <li>Una corrida que ya termino COMPLETED no se vuelve a ejecutar (codigo 0): levantar de nuevo el
 *       contenedor del batch no duplica la carga.</li>
 * </ul>
 * <p>Codigos de salida: 0 bien, 1 fallo tecnico tras las reejecuciones, 2 parametros invalidos,
 * 3 revision manual.</p>
 */
@Component
// Se apaga en las pruebas (banco.batch.lanzador=false): alli los Jobs los dispara el test,
// no el arranque de la aplicacion.
@ConditionalOnProperty(name = "banco.batch.lanzador", havingValue = "true", matchIfMissing = true)
public class LanzadorDeJobs implements ApplicationRunner, ExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(LanzadorDeJobs.class);

    /** Alias corto -> nombre real del Job, para no escribir el nombre completo en la consola. */
    private static final Map<String, String> ALIAS = new LinkedHashMap<>();

    static {
        ALIAS.put("transacciones", Constantes.JOB_TRANSACCIONES_DIARIAS);
        ALIAS.put("intereses", Constantes.JOB_INTERESES_MENSUALES);
        ALIAS.put("estados", Constantes.JOB_ESTADOS_CUENTA_ANUALES);
        ALIAS.put("completa", Constantes.JOB_MIGRACION_COMPLETA);
    }

    /** Carpeta del repositorio legacy que se procesa si no se indica otra. */
    private static final String DATASET_POR_DEFECTO = "semana_1";
    private static final String ENTRADA_POR_DEFECTO = "data";
    private static final String SALIDA_POR_DEFECTO = "salida";

    private final JobLauncher jobLauncher;
    private final JobExplorer jobExplorer;
    private final Map<String, Job> jobs;
    private final PropiedadesBatch propiedades;
    private int codigoSalida;

    public LanzadorDeJobs(JobLauncher jobLauncher, JobExplorer jobExplorer,
                          Map<String, Job> jobs, PropiedadesBatch propiedades) {
        this.jobLauncher = jobLauncher;
        this.jobExplorer = jobExplorer;
        this.jobs = jobs;
        this.propiedades = propiedades;
    }

    @Override
    public void run(ApplicationArguments argumentos) throws Exception {
        String alias = primerValor(argumentos, "job", "completa");
        String nombreJob = ALIAS.get(alias.toLowerCase());
        if (nombreJob == null) {
            log.error("Job desconocido: '{}'. Opciones validas: {}", alias, ALIAS.keySet());
            codigoSalida = 2;
            return;
        }

        Job job = jobs.get(nombreJob);
        if (job == null) {
            log.error("El Job '{}' no esta registrado en el contexto", nombreJob);
            codigoSalida = 2;
            return;
        }

        Path carpetaEntrada = resolverEntrada(argumentos);
        if (!Files.isDirectory(carpetaEntrada)) {
            log.error("La carpeta de entrada no existe: {}", carpetaEntrada.toAbsolutePath());
            codigoSalida = 2;
            return;
        }
        Path carpetaSalida = Path.of(primerValor(argumentos, "salida", SALIDA_POR_DEFECTO));
        Files.createDirectories(carpetaSalida);

        // La etiqueta de corrida es lo que decide si esto es una migracion nueva o el
        // reintento de una anterior: los JobParameters identifican a la JobInstance, de modo
        // que repetir la etiqueta hace que Spring Batch retome la instancia ya existente.
        String etiquetaCorrida = primerValor(argumentos, "corrida", LocalDateTime.now().toString());

        JobParameters parametros = new JobParametersBuilder()
                .addString(Constantes.PARAM_ENTRADA, carpetaEntrada.toString())
                .addString(Constantes.PARAM_SALIDA, carpetaSalida.toString())
                .addString(Constantes.PARAM_EJECUCION, etiquetaCorrida)
                .toJobParameters();

        registrarConfiguracionEfectiva(nombreJob, carpetaEntrada, etiquetaCorrida);
        avisarSiEsReanudacion(nombreJob, parametros);

        JobExecution ejecucion;
        try {
            ejecucion = jobLauncher.run(job, parametros);
            int reejecuciones = 0;
            while (ejecucion.getStatus() == BatchStatus.FAILED && !requiereRevisionManual(ejecucion)
                    && reejecuciones < propiedades.getReejecucionesAutomaticas()) {
                reejecuciones++;
                log.warn("REEJECUCION AUTOMATICA {}/{}: la ejecucion {} termino FAILED por un fallo tecnico. "
                                + "Se retoma la misma instancia (corrida={}) en {} ms.", reejecuciones,
                        propiedades.getReejecucionesAutomaticas(), ejecucion.getId(), etiquetaCorrida,
                        propiedades.getEsperaReejecucionMs());
                Thread.sleep(propiedades.getEsperaReejecucionMs());
                ejecucion = jobLauncher.run(job, parametros);
            }
        } catch (JobInstanceAlreadyCompleteException yaPublicada) {
            // Volver a levantar el contenedor del batch con la misma etiqueta no migra dos veces.
            log.info("La corrida '{}' de '{}' ya termino COMPLETED: no se vuelve a migrar. Para una carga nueva, "
                    + "otra --corrida.", etiquetaCorrida, nombreJob);
            return;
        } catch (JobRestartException limiteDeArranques) {
            log.error("La corrida '{}' de '{}' no se puede retomar: {}", etiquetaCorrida, nombreJob,
                    limiteDeArranques.getMessage());
            codigoSalida = 1;
            return;
        }

        if (!ejecucion.getStatus().isUnsuccessful()) {
            log.info("Job '{}' finalizado con estado {}", nombreJob, ejecucion.getStatus());
        } else if (requiereRevisionManual(ejecucion)) {
            log.error("Job '{}' finalizado con estado {} y derivado a REVISION MANUAL: {}. No se reejecuta solo; "
                            + "una vez corregido el origen, relanzar con --corrida={}", nombreJob,
                    ejecucion.getStatus(), ejecucion.getExecutionContext().getString(Constantes.CTX_REVISION_MANUAL),
                    etiquetaCorrida);
            codigoSalida = 3;
        } else {
            log.error("Job '{}' finalizado con estado {}. Para reintentarlo sobre la misma "
                            + "instancia: volver a lanzar con --corrida={}",
                    nombreJob, ejecucion.getStatus(), etiquetaCorrida);
            codigoSalida = 1;
        }
    }

    /** Un fallo de calidad o de integridad no se arregla reintentando: lo marcan el decisor y la conciliacion. */
    private static boolean requiereRevisionManual(JobExecution ejecucion) {
        return ejecucion.getExecutionContext() != null
                && ejecucion.getExecutionContext().containsKey(Constantes.CTX_REVISION_MANUAL);
    }

    /**
     * Deja en el log la configuracion de escalado con la que de verdad se corrio. Es lo que
     * hace comparables dos mediciones: sin esta linea, un log que dice "1000 items en 900 ms"
     * no permite saber si fue con uno o con tres hilos.
     */
    private void registrarConfiguracionEfectiva(String nombreJob, Path entrada, String corrida) {
        log.info("Configuracion efectiva | job={} entrada={} corrida={}", nombreJob, entrada, corrida);
        log.info("Configuracion efectiva | chunk={} hilosPorPaso={} hilosDeFlujos={} colaChunks={}",
                propiedades.getTamanoChunk(), propiedades.getHilosPorPaso(),
                propiedades.getHilosDeFlujos(), propiedades.getCapacidadCola());
        log.info("Configuracion efectiva | limiteOmisiones={} limiteReintentos={} backoff={}ms x{} (max {}ms)",
                propiedades.getLimiteOmisiones(), propiedades.getLimiteReintentos(),
                propiedades.getBackoffInicialMs(), propiedades.getBackoffMultiplicador(),
                propiedades.getBackoffMaximoMs());
        log.info("Configuracion efectiva | umbralAlerta={} umbralRechazo={}",
                propiedades.getUmbralAlertaOmision(), propiedades.getUmbralRechazoOmision());
    }

    /** Avisa al operador cuando la corrida que va a lanzar reanuda una instancia anterior. */
    private void avisarSiEsReanudacion(String nombreJob, JobParameters parametros) {
        JobInstance instancia = jobExplorer.getJobInstance(nombreJob, parametros);
        if (instancia == null) {
            return;
        }
        List<JobExecution> previas = jobExplorer.getJobExecutions(instancia);
        if (previas.isEmpty()) {
            return;
        }
        log.warn("RE-EJECUCION: la instancia {} ya tiene {} ejecucion(es) previa(s) {}. "
                        + "Spring Batch retomara los Steps que no hayan terminado bien.",
                instancia.getInstanceId(), previas.size(),
                previas.stream().map(e -> e.getId() + ":" + e.getStatus()).toList());
    }

    private Path resolverEntrada(ApplicationArguments argumentos) {
        List<String> entrada = argumentos.getOptionValues("entrada");
        if (entrada != null && !entrada.isEmpty()) {
            return Path.of(entrada.get(0));
        }
        return Path.of(ENTRADA_POR_DEFECTO, primerValor(argumentos, "dataset", DATASET_POR_DEFECTO));
    }

    private static String primerValor(ApplicationArguments argumentos, String opcion, String porDefecto) {
        List<String> valores = argumentos.getOptionValues(opcion);
        return valores == null || valores.isEmpty() ? porDefecto : valores.get(0);
    }

    @Override
    public int getExitCode() {
        return codigoSalida;
    }
}
