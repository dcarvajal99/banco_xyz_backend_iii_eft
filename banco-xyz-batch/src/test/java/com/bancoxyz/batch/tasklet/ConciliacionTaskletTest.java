package com.bancoxyz.batch.tasklet;

import com.bancoxyz.common.Constantes;
import com.bancoxyz.entity.Conciliacion;
import com.bancoxyz.repository.ConciliacionRepository;
import com.bancoxyz.repository.CuentaInteresRepository;
import com.bancoxyz.repository.MovimientoAnualRepository;
import com.bancoxyz.repository.RegistroRechazadoRepository;
import com.bancoxyz.repository.TransaccionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.test.MetaDataInstanceFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Las cuatro igualdades de la conciliacion, con contadores armados a mano para provocar cada descuadre. */
class ConciliacionTaskletTest {

    private final TransaccionRepository transacciones = mock(TransaccionRepository.class);
    private final RegistroRechazadoRepository rechazados = mock(RegistroRechazadoRepository.class);
    private final ConciliacionRepository conciliaciones = mock(ConciliacionRepository.class);
    private final ConciliacionTasklet tasklet = new ConciliacionTasklet(transacciones,
            mock(CuentaInteresRepository.class), mock(MovimientoAnualRepository.class), rechazados, conciliaciones);

    private JobExecution job;
    private StepExecution migracion;
    private ChunkContext contexto;

    @BeforeEach
    void preparar(@TempDir Path carpeta) throws Exception {
        // 10 lineas de datos.
        Files.write(carpeta.resolve(Constantes.ARCHIVO_TRANSACCIONES),
                List.of("id,fecha,monto,tipo", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10"));
        job = MetaDataInstanceFactory.createJobExecution("job", 1L, 7L, new JobParametersBuilder()
                .addString(Constantes.PARAM_ENTRADA, carpeta.toString())
                .addString(Constantes.PARAM_SALIDA, carpeta.toString()).toJobParameters());
        migracion = job.createStepExecution(Constantes.STEP_PROCESAR_TRANSACCIONES);
        migracion.getExecutionContext().putString(Constantes.CTX_PASO_DE_MIGRACION, Constantes.STEP_PROCESAR_TRANSACCIONES);
        StepExecution propio = job.createStepExecution(Constantes.STEP_CONCILIACION);
        contexto = new ChunkContext(new StepContext(propio));
    }

    /** 10 lineas: 6 migradas, 1 filtrada, 3 omitidas (2 al procesar y 1 al leer). */
    private void contadoresCorrectos() {
        migracion.setReadCount(9);
        migracion.setReadSkipCount(1);
        migracion.setWriteCount(6);
        migracion.setFilterCount(1);
        migracion.setProcessSkipCount(2);
        when(transacciones.countByJobExecutionId(7L)).thenReturn(6L);
        when(rechazados.countByJobExecutionIdAndArchivo(eq(7L), eq(Constantes.ARCHIVO_TRANSACCIONES))).thenReturn(4L);
    }

    private Conciliacion guardada() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Conciliacion>> captor = ArgumentCaptor.forClass(List.class);
        verify(conciliaciones).saveAll(captor.capture());
        return captor.getValue().get(0);
    }

    @Test
    @DisplayName("Totales que cuadran: queda registrada como CUADRA y el paso termina bien")
    void cuadra() throws Exception {
        contadoresCorrectos();

        tasklet.execute(new StepContribution(migracion), contexto);

        assertThat(guardada().isCuadra()).isTrue();
        assertThat(job.getExecutionContext().containsKey(Constantes.CTX_REVISION_MANUAL)).isFalse();
    }

    @Test
    @DisplayName("Una fila mas en la tabla que las migradas (reintento que duplico): no cuadra, falla y va a revision manual")
    void filasDeMasEnLaTabla() {
        contadoresCorrectos();
        when(transacciones.countByJobExecutionId(anyLong())).thenReturn(7L);

        assertThatThrownBy(() -> tasklet.execute(new StepContribution(migracion), contexto))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("la tabla tiene 7 filas y se migraron 6");
        assertThat(guardada().isCuadra()).isFalse();
        assertThat(job.getExecutionContext().containsKey(Constantes.CTX_REVISION_MANUAL)).isTrue();
    }

    @Test
    @DisplayName("El lector se salto lineas del archivo: no cuadra")
    void lineasSinLeer() {
        contadoresCorrectos();
        migracion.setReadCount(8);
        migracion.setWriteCount(5);
        when(transacciones.countByJobExecutionId(anyLong())).thenReturn(5L);

        assertThatThrownBy(() -> tasklet.execute(new StepContribution(migracion), contexto))
                .hasMessageContaining("el archivo trae 10 lineas y se leyeron 9");
    }

    @Test
    @DisplayName("Un rechazo sin registrar en la bitacora: no cuadra")
    void rechazoSinBitacora() {
        contadoresCorrectos();
        when(rechazados.countByJobExecutionIdAndArchivo(anyLong(), eq(Constantes.ARCHIVO_TRANSACCIONES))).thenReturn(3L);

        assertThatThrownBy(() -> tasklet.execute(new StepContribution(migracion), contexto))
                .hasMessageContaining("la bitacora tiene 3 rechazos y hubo 4");
    }

    @Test
    @DisplayName("Las particiones no se concilian por separado: solo el Step gestor representa al archivo")
    void ignoraLasParticiones() throws Exception {
        contadoresCorrectos();
        StepExecution particion = job.createStepExecution(Constantes.STEP_PROCESAR_TRANSACCIONES
                + Constantes.SUFIJO_WORKER + Constantes.SUFIJO_PARTICION + "0");
        particion.getExecutionContext().putString(Constantes.CTX_PASO_DE_MIGRACION, "x");
        particion.setReadCount(3);

        tasklet.execute(new StepContribution(migracion), contexto);

        assertThat(guardada().isCuadra()).isTrue();
    }
}
