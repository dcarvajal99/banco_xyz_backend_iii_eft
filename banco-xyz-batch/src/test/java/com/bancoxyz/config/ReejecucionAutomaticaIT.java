package com.bancoxyz.config;

import com.bancoxyz.common.Constantes;
import com.bancoxyz.repository.TransaccionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Politica de reejecucion automatica, de punta a punta: la base "cae" durante toda la primera ejecucion
 * (se agotan los reintentos del chunk y el Job termina FAILED), el lanzador espera y retoma la misma
 * JobInstance, la limpieza previa deja la base sin restos y la segunda ejecucion termina COMPLETED con
 * exactamente los datos de una corrida normal.
 */
@SpringBootTest(properties = "banco.batch.simular-fallo-critico=true")
class ReejecucionAutomaticaIT {

    private static final Path PRUEBA = Path.of("src", "test", "resources", "data", "prueba");

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    private JobExplorer jobExplorer;

    @Autowired
    private Map<String, Job> jobs;

    @Autowired
    private PropiedadesBatch propiedades;

    @Autowired
    private TransaccionRepository transacciones;

    @Test
    @DisplayName("Fallo critico en la primera ejecucion: el lanzador la reejecuta sola y la corrida termina COMPLETED sin duplicar")
    void reejecutaYCompletaSinDuplicar(@TempDir Path salida) throws Exception {
        LanzadorDeJobs lanzador = new LanzadorDeJobs(jobLauncher, jobExplorer, jobs, propiedades);

        lanzador.run(new DefaultApplicationArguments("--job=transacciones", "--entrada=" + PRUEBA,
                "--salida=" + salida, "--corrida=caida-de-la-base"));

        assertThat(lanzador.getExitCode()).isZero();
        JobInstance instancia = jobExplorer.getLastJobInstance(Constantes.JOB_TRANSACCIONES_DIARIAS);
        List<JobExecution> ejecuciones = jobExplorer.getJobExecutions(instancia).stream()
                .sorted(Comparator.comparing(JobExecution::getId)).toList();
        assertThat(ejecuciones).extracting(JobExecution::getStatus)
                .containsExactly(BatchStatus.FAILED, BatchStatus.COMPLETED);
        assertThat(transacciones.countByJobExecutionId(ejecuciones.get(0).getId())).isZero();
        assertThat(transacciones.countByJobExecutionId(ejecuciones.get(1).getId())).isEqualTo(5);
    }
}
