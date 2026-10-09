package com.bancoxyz.cuentas.cierre;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.bancoxyz.cuentas.autenticacion.CodificadorDeSecretos;
import com.bancoxyz.cuentas.config.PropiedadesCuentas;
import com.bancoxyz.cuentas.error.ErrorDeNegocio;
import com.bancoxyz.cuentas.tarjeta.Luhn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Abre en el esquema cuentas las cuentas del cierre publicado ({@code migracion.cierre_cuenta}: ya trae una fila por
 * cuenta y el cliente_id estable del maestro, asi que aqui no hay reglas de la migracion).
 *
 * <ul>
 *   <li>Una cuenta existente no se toca: su saldo ya cambio con retiros y pagos en linea.</li>
 *   <li>{@code insert ... on conflict do nothing}: segura si varias replicas arrancan a la vez.</li>
 *   <li>Con datos de demostracion: una tarjeta por cuenta de ahorro con titular (PIN comun, ver README).</li>
 * </ul>
 */
@Component
public class SincronizadorDeCierre {

    private static final Logger log = LoggerFactory.getLogger(SincronizadorDeCierre.class);

    record FilaCierre(long cuentaId, Long clienteId, String titular, String tipo, BigDecimal saldoFinal,
                      BigDecimal tasaMensual, BigDecimal interes, boolean anomalia, String observacion) {
    }

    private final LectorDeCierres cierres;
    private final JdbcClient jdbc;
    private final CodificadorDeSecretos codificador;
    private final PropiedadesCuentas propiedades;
    private final Clock reloj;

    public SincronizadorDeCierre(LectorDeCierres cierres, JdbcClient jdbc, CodificadorDeSecretos codificador,
                                 PropiedadesCuentas propiedades, Clock reloj) {
        this.cierres = cierres;
        this.jdbc = jdbc;
        this.codificador = codificador;
        this.propiedades = propiedades;
        this.reloj = reloj;
    }

    @Transactional
    public ResumenSincronizacion sincronizar() {
        Optional<CierrePublicado> vigente;
        try {
            vigente = cierres.vigente(Conjunto.INTERESES);
        } catch (ErrorDeNegocio sinTablas) {
            log.warn("Sin cierre publicado: el batch aun no corre. Ejecutelo y reinicie el servicio.");
            return ResumenSincronizacion.sinCierre();
        }
        if (vigente.isEmpty()) {
            log.warn("Sin cierre publicado: no hay una ejecucion COMPLETED con cierre de cuentas.");
            return ResumenSincronizacion.sinCierre();
        }
        CierrePublicado cierre = vigente.get();
        LocalDateTime ahora = LocalDateTime.now(reloj).truncatedTo(ChronoUnit.SECONDS);
        List<FilaCierre> filas = jdbc.sql("""
                        select cuenta_id, cliente_id, titular, tipo, saldo_final, tasa_mensual, interes, anomalia, observacion
                        from migracion.cierre_cuenta where job_execution_id = :job order by cuenta_id""")
                .param("job", cierre.jobExecutionId()).query(FilaCierre.class).list();

        int nuevas = 0;
        for (FilaCierre f : filas) {
            nuevas += jdbc.sql("""
                            insert into cuentas.cuenta (id, cliente_id, titular, tipo, estado, origen, saldo_disponible,
                                saldo_cierre, tasa_mensual, interes_cierre, anomalia, observacion, cierre_job_execution_id,
                                abierta_en, version, actualizada_en)
                            values (:id, :cliente, :titular, :tipo, 'ACTIVA', 'MIGRACION', :saldo, :saldo, :tasa, :interes,
                                :anomalia, :observacion, :job, :ahora, 0, :ahora)
                            on conflict do nothing""")
                    .param("id", f.cuentaId()).param("cliente", f.clienteId()).param("titular", f.titular())
                    .param("tipo", f.tipo()).param("saldo", f.saldoFinal()).param("tasa", f.tasaMensual())
                    .param("interes", f.interes()).param("anomalia", f.anomalia()).param("observacion", f.observacion())
                    .param("job", cierre.jobExecutionId()).param("ahora", ahora).update();
        }

        int tarjetasNuevas = 0;
        List<String> credenciales = new ArrayList<>();
        if (propiedades.datosDemo().habilitados()) {
            String pin = codificador.cifrar(propiedades.datosDemo().pin());
            for (FilaCierre f : filas) {
                if (!"ahorro".equals(f.tipo()) || f.clienteId() == null) {
                    continue;
                }
                String pan = numeroDeTarjeta(f.cuentaId());
                tarjetasNuevas += jdbc.sql("""
                                insert into cuentas.tarjeta (numero_hash, ultimos4, cuenta_id, pin_hash, intentos_fallidos,
                                    bloqueada, limite_diario)
                                values (:hash, :ultimos4, :cuenta, :pin, 0, false, :limite) on conflict do nothing""")
                        .param("hash", codificador.huellaDeTarjeta(pan)).param("ultimos4", pan.substring(12))
                        .param("cuenta", f.cuentaId()).param("pin", pin)
                        .param("limite", propiedades.cajero().limiteDiario()).update();
                credenciales.add("  tarjeta %s cuenta %d %s".formatted(pan, f.cuentaId(), f.titular()));
            }
        }
        log.info("Cierre publicado: ejecucion {} ({}), calidad {} | {} cuentas en el cierre, {} nuevas | {} tarjetas nuevas",
                cierre.jobExecutionId(), cierre.jobNombre(), cierre.calidad(), filas.size(), nuevas, tarjetasNuevas);
        if (!credenciales.isEmpty()) {
            log.info("Tarjetas de demostracion (PIN en el README):\n{}", String.join("\n", credenciales));
        }
        return new ResumenSincronizacion(true, cierre.jobExecutionId(), cierre.calidad(), filas.size(), nuevas,
                tarjetasNuevas);
    }

    /** PAN de demostracion: 4 + cuenta con 14 digitos + digito Luhn (cuenta 101 -> 4000000000001018). */
    public static String numeroDeTarjeta(long cuentaId) {
        return Luhn.conDigitoVerificador("4" + String.format("%014d", cuentaId));
    }
}
