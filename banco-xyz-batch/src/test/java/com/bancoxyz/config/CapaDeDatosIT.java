package com.bancoxyz.config;

import com.bancoxyz.common.Constantes;
import com.bancoxyz.entity.CierreCuenta;
import com.bancoxyz.entity.ClienteMigrado;
import com.bancoxyz.entity.Conciliacion;
import com.bancoxyz.repository.CierreCuentaRepository;
import com.bancoxyz.repository.ClienteMigradoRepository;
import com.bancoxyz.repository.ConciliacionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La capa de datos que el batch deja para los microservicios: conciliacion de totales de control, cierre
 * con una fila por cuenta y maestro de clientes con ids estables (esquema {@code migracion}).
 */
@SpringBootTest
class CapaDeDatosIT {

    private static final Path PRUEBA = Path.of("src", "test", "resources", "data", "prueba");

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    @Qualifier("calculoInteresesMensualesJob")
    private Job jobIntereses;

    @Autowired
    @Qualifier("migracionCompletaJob")
    private Job jobCompleta;

    @Autowired
    private ConciliacionRepository conciliaciones;

    @Autowired
    private CierreCuentaRepository cierres;

    @Autowired
    private ClienteMigradoRepository clientes;

    private JobExecution correr(Job job, Path entrada, Path salida) throws Exception {
        return jobLauncher.run(job, new JobParametersBuilder()
                .addString(Constantes.PARAM_ENTRADA, entrada.toString())
                .addString(Constantes.PARAM_SALIDA, salida.toString())
                .addString(Constantes.PARAM_EJECUCION, LocalDateTime.now() + "-" + UUID.randomUUID())
                .toJobParameters());
    }

    @Test
    @DisplayName("Conciliacion: cada linea de los tres archivos termina migrada, filtrada u omitida, y la bitacora lo respalda")
    void conciliaLosTresArchivos(@TempDir Path salida) throws Exception {
        JobExecution ejecucion = correr(jobCompleta, PRUEBA, salida);

        assertThat(ejecucion.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        List<Conciliacion> resultado = conciliaciones.findByJobExecutionIdOrderByIdAsc(ejecucion.getId());
        assertThat(resultado).extracting(Conciliacion::getArchivo).containsExactlyInAnyOrder(
                Constantes.ARCHIVO_TRANSACCIONES, Constantes.ARCHIVO_INTERESES, Constantes.ARCHIVO_CUENTAS_ANUALES);
        assertThat(resultado).allSatisfy(c -> {
            assertThat(c.isCuadra()).as(c.getArchivo() + ": " + c.getDiferencias()).isTrue();
            assertThat(c.getLineasArchivo()).isEqualTo(c.getLeidas());
            assertThat(c.getLeidas()).isEqualTo(c.getMigradas() + c.getFiltradas() + c.getOmitidas());
            assertThat(c.getFilasEnTabla()).isEqualTo(c.getMigradas());
        });
        Conciliacion intereses = resultado.stream()
                .filter(c -> c.getArchivo().equals(Constantes.ARCHIVO_INTERESES)).findFirst().orElseThrow();
        // 9 lineas: 5 migradas, 1 filtrada (fila identica repetida) y 3 omitidas (edad vacia, saldo vacio, edad 100).
        assertThat(intereses.getLineasArchivo()).isEqualTo(9);
        assertThat(intereses.getMigradas()).isEqualTo(5);
        assertThat(intereses.getFiltradas()).isEqualTo(1);
        assertThat(intereses.getOmitidas()).isEqualTo(3);
        assertThat(Files.readAllLines(salida.resolve(Constantes.SALIDA_CONCILIACION))).hasSize(4);
    }

    @Test
    @DisplayName("Cierre publicado: una fila por cuenta, la primera del archivo, con su cliente; el titular sin identificar queda sin cliente")
    void publicaUnaFilaPorCuenta(@TempDir Path salida) throws Exception {
        JobExecution ejecucion = correr(jobIntereses, PRUEBA, salida);

        assertThat(ejecucion.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Map<Long, CierreCuenta> cierre = cierres.findByJobExecutionIdOrderByCuentaIdAsc(ejecucion.getId()).stream()
                .collect(Collectors.toMap(CierreCuenta::getCuentaId, Function.identity()));
        assertThat(cierre).containsOnlyKeys(101L, 102L, 105L, 106L, 108L);
        // Linea del archivo, contando la cabecera: la primera fila de datos es la 2 (la 101 repetida esta en la 10).
        assertThat(cierre.get(101L).getLineaOrigen()).isEqualTo(2);
        assertThat(cierre.get(101L).getSaldoFinal()).isEqualByComparingTo("5025.00");
        assertThat(cierre.get(108L).getTitular()).isEqualTo(Constantes.TITULAR_SIN_IDENTIFICAR);
        assertThat(cierre.get(108L).getClienteId()).isNull();

        ClienteMigrado john = clientes.findByNombre("John Doe").orElseThrow();
        assertThat(cierre.get(101L).getClienteId()).isEqualTo(john.getId());
        assertThat(cierre.get(106L).getClienteId()).isEqualTo(john.getId());
        assertThat(john.getCuentasEnCierre()).isEqualTo(2);
        assertThat(clientes.findByNombre("Unknown")).isEmpty();
        assertThat(clientes.findByNombre(Constantes.TITULAR_SIN_IDENTIFICAR)).isEmpty();
        assertThat(Files.readAllLines(salida.resolve(Constantes.SALIDA_CIERRE))).hasSize(6);
    }

    @Test
    @DisplayName("Maestro de clientes: una segunda carga no crea clientes nuevos ni cambia sus ids")
    void losIdsDeClienteSonEstables(@TempDir Path salida) throws Exception {
        correr(jobIntereses, PRUEBA, salida.resolve("a"));
        Map<String, Long> antes = idsPorNombre();

        JobExecution segunda = correr(jobIntereses, PRUEBA, salida.resolve("b"));

        assertThat(segunda.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(idsPorNombre()).isEqualTo(antes);
        assertThat(clientes.findByNombre("Jane Smith").orElseThrow().getUltimaEjecucion()).isEqualTo(segunda.getId());
        assertThat(cierres.findByJobExecutionIdOrderByCuentaIdAsc(segunda.getId()))
                .extracting(CierreCuenta::getClienteId).contains(antes.get("Jane Smith"), antes.get("Charlie Green"));
    }

    private Map<String, Long> idsPorNombre() {
        return clientes.findAll().stream().collect(Collectors.toMap(ClienteMigrado::getNombre, ClienteMigrado::getId));
    }
}
