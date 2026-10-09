package com.bancoxyz.batch.tasklet;

import com.bancoxyz.common.Constantes;
import com.bancoxyz.entity.CierreCuenta;
import com.bancoxyz.entity.ClienteMigrado;
import com.bancoxyz.entity.CuentaInteres;
import com.bancoxyz.repository.CierreCuentaRepository;
import com.bancoxyz.repository.ClienteMigradoRepository;
import com.bancoxyz.repository.CuentaInteresRepository;
import com.bancoxyz.util.EscritorCsv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Ultimo Step del calculo de intereses: publica el cierre que copian los microservicios.
 *
 * <p>Produce dos cosas en el esquema {@code migracion}:</p>
 * <ul>
 *   <li><b>{@code cierre_cuenta}</b>: una fila por cuenta, la primera aparicion en el archivo (menor linea),
 *       con su saldo final ya liquidado y el id de su cliente. cuentas-service abre las cuentas desde aqui.</li>
 *   <li><b>{@code cliente_migrado}</b>: un cliente por titular identificado, con un id que se asigna la
 *       primera vez que el titular aparece y no cambia despues. clientes-service crea los clientes desde
 *       aqui. Los nuevos se numeran en orden alfabetico, asi que dos bases cargadas con los mismos archivos
 *       quedan con los mismos ids.</li>
 * </ul>
 *
 * <p>Solo corre si el decisor dejo pasar la corrida: un cierre de calidad inaceptable nunca llega a los
 * servicios. Los servicios toman la ejecucion COMPLETED mas reciente con filas en {@code cierre_cuenta}.</p>
 */
@Component
public class PublicacionDelCierreTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(PublicacionDelCierreTasklet.class);

    private final CuentaInteresRepository cuentas;
    private final CierreCuentaRepository cierres;
    private final ClienteMigradoRepository clientes;

    public PublicacionDelCierreTasklet(CuentaInteresRepository cuentas, CierreCuentaRepository cierres,
                                       ClienteMigradoRepository clientes) {
        this.cuentas = cuentas;
        this.cierres = cierres;
        this.clientes = clientes;
    }

    @Override
    public RepeatStatus execute(StepContribution contribucion, ChunkContext contexto) throws Exception {
        Long jobId = contexto.getStepContext().getStepExecution().getJobExecutionId();
        Path salida = Path.of(String.valueOf(contexto.getStepContext().getJobParameters().get(Constantes.PARAM_SALIDA)));

        // Ordenadas por cuenta y linea: la primera de cada cuenta es la canonica.
        Map<Long, CuentaInteres> canonicas = new LinkedHashMap<>();
        Map<Long, Integer> filasPorCuenta = new LinkedHashMap<>();
        for (CuentaInteres fila : cuentas.findByJobExecutionIdOrderByCuentaIdAscLineaOrigenAsc(jobId)) {
            canonicas.putIfAbsent(fila.getCuentaId(), fila);
            filasPorCuenta.merge(fila.getCuentaId(), 1, Integer::sum);
        }

        // Titulares identificados, en orden alfabetico; sus cuentas en orden de id.
        Map<String, List<CuentaInteres>> porTitular = new TreeMap<>();
        canonicas.values().stream()
                .filter(c -> !Constantes.TITULAR_SIN_IDENTIFICAR.equals(c.getNombre()))
                .forEach(c -> porTitular.computeIfAbsent(c.getNombre(), n -> new ArrayList<>()).add(c));

        long siguienteId = clientes.mayorId() + 1;
        int clientesNuevos = 0;
        Map<String, Long> idPorTitular = new LinkedHashMap<>();
        for (Map.Entry<String, List<CuentaInteres>> titular : porTitular.entrySet()) {
            ClienteMigrado cliente = clientes.findByNombre(titular.getKey()).orElse(null);
            if (cliente == null) {
                cliente = new ClienteMigrado(siguienteId++, titular.getKey(), jobId);
                clientesNuevos++;
            }
            cliente.publicar(titular.getValue().get(0).getEdad(), titular.getValue().size(), jobId);
            idPorTitular.put(titular.getKey(), clientes.save(cliente).getId());
        }

        List<CierreCuenta> cierre = canonicas.values().stream()
                .map(c -> CierreCuenta.desde(c, idPorTitular.get(c.getNombre()), filasPorCuenta.get(c.getCuentaId())))
                .toList();
        cierres.saveAll(cierre);

        EscritorCsv.escribir(salida.resolve(Constantes.SALIDA_CIERRE),
                "cuenta_id,cliente_id,titular,tipo,saldo_inicial,tasa_mensual,interes,saldo_final,filas_en_origen,linea_origen,anomalia",
                cierre.stream().map(c -> EscritorCsv.fila(c.getCuentaId(), c.getClienteId() == null ? "" : c.getClienteId(),
                        c.getTitular(), c.getTipo(), EscritorCsv.importe(c.getSaldoInicial()), c.getTasaMensual().toPlainString(),
                        EscritorCsv.importe(c.getInteres()), EscritorCsv.importe(c.getSaldoFinal()), c.getFilasEnOrigen(),
                        c.getLineaOrigen(), c.isAnomalia() ? "SI" : "NO")).toList());

        long sinTitular = cierre.stream().filter(c -> c.getClienteId() == null).count();
        log.info("Cierre publicado | ejecucion {} | {} cuentas ({} sin titular identificado) de {} filas validas | "
                        + "{} clientes ({} nuevos, total del maestro {})", jobId, cierre.size(), sinTitular,
                filasPorCuenta.values().stream().mapToInt(Integer::intValue).sum(), idPorTitular.size(), clientesNuevos,
                clientes.count());
        contribucion.incrementWriteCount(cierre.size());
        return RepeatStatus.FINISHED;
    }
}
