package com.bancoxyz.batch.writer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.core.scope.context.StepSynchronizationManager;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.dao.TransientDataAccessResourceException;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Decorador de {@code ItemWriter} que simula una caida transitoria de la base de datos.
 *
 * <p>Existe para poder <b>demostrar</b> el {@code RetryPolicy}: sin un fallo real no hay
 * forma de evidenciar que el chunk se reintenta y termina bien. Cuando la propiedad
 * {@code banco.batch.simular-fallo-transitorio} esta activa, el primer intento de
 * escritura lanza una {@link TransientDataAccessResourceException} (el tipo de error que
 * Spring clasifica como recuperable) y los siguientes delegan normalmente.</p>
 *
 * <p>El interruptor es un {@link AtomicBoolean} y no un {@code boolean}: con el Step a tres
 * hilos, tres escrituras entran aqui a la vez y un {@code boolean} sin sincronizar dejaria
 * que las tres vieran {@code false} y fallaran. Con {@code compareAndSet} el fallo se
 * inyecta <b>exactamente una vez</b>, que es lo que hace la evidencia reproducible: un
 * unico reintento, no tres carreras distintas segun como caiga la ejecucion.</p>
 *
 * <p>Con {@code banco.batch.simular-fallo-critico} la caida no es de un instante sino de toda la primera
 * ejecucion del Job: cada escritura de esa ejecucion falla, los reintentos se agotan, el Step termina
 * FAILED y con el la corrida. Las ejecuciones siguientes escriben normal. Es lo que permite demostrar
 * la reejecucion automatica del lanzador, que retoma la misma JobInstance.</p>
 *
 * <p>Viene desactivado por defecto: solo se enciende en la corrida de demostracion.</p>
 *
 * @param <T> tipo de entidad que se persiste
 */
public class EscritorConFalloSimulado<T> implements ItemWriter<T> {

    private static final Logger log = LoggerFactory.getLogger(EscritorConFalloSimulado.class);

    private final ItemWriter<T> delegado;
    private final boolean activo;
    private final boolean falloCritico;
    private final AtomicBoolean yaFallo = new AtomicBoolean(false);
    /** Primera ejecucion de Job que llego a este escritor: la unica que sufre el fallo critico. */
    private final AtomicLong ejecucionAfectada = new AtomicLong(-1);

    public EscritorConFalloSimulado(ItemWriter<T> delegado, boolean activo) {
        this(delegado, activo, false);
    }

    public EscritorConFalloSimulado(ItemWriter<T> delegado, boolean activo, boolean falloCritico) {
        this.delegado = delegado;
        this.activo = activo;
        this.falloCritico = falloCritico;
    }

    @Override
    public void write(Chunk<? extends T> chunk) throws Exception {
        if (falloCritico) {
            StepContext paso = StepSynchronizationManager.getContext();
            if (paso != null) {
                long ejecucion = paso.getStepExecution().getJobExecutionId();
                ejecucionAfectada.compareAndSet(-1, ejecucion);
                if (ejecucionAfectada.get() == ejecucion) {
                    throw new TransientDataAccessResourceException(
                            "Base de datos no disponible durante toda la ejecucion " + ejecucion
                                    + " (fallo critico simulado)");
                }
            }
        }
        if (activo && yaFallo.compareAndSet(false, true)) {
            log.warn("Simulando caida transitoria de la base de datos en el primer chunk ({} items). "
                    + "El RetryPolicy debe reintentar y completar la escritura.", chunk.size());
            throw new TransientDataAccessResourceException(
                    "Conexion perdida con la base de datos (fallo transitorio simulado)");
        }
        delegado.write(chunk);
    }
}
