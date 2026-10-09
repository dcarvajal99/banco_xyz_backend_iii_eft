package com.bancoxyz.cuentas.pago;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

import com.bancoxyz.cuentas.cuenta.Cuenta;
import com.bancoxyz.cuentas.cuenta.RepositorioCuentas;
import com.bancoxyz.cuentas.operacion.Operacion;
import com.bancoxyz.cuentas.operacion.RepositorioOperaciones;
import com.bancoxyz.cuentas.outbox.Outbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Aplica un pago solicitado: valida, mueve los saldos y publica el resultado, todo en UNA transaccion local.
 *
 * <ul>
 *   <li><b>Idempotente</b>: el eventoId queda en evento_procesado y el pagoId en pago_aplicado; Kafka puede entregar
 *       dos veces y el pago se aplica una sola.</li>
 *   <li><b>Sin deadlocks</b>: las dos cuentas de una transferencia se bloquean siempre en orden de id.</li>
 *   <li><b>Consistencia con pagos-service</b>: el resultado sale por el outbox en la misma transaccion que los saldos;
 *       pagos-service lo recibe por {@value Topicos#MOVIMIENTOS} (consistencia eventual).</li>
 * </ul>
 */
@Service
public class ServicioDePagos {

    private static final Logger log = LoggerFactory.getLogger(ServicioDePagos.class);

    /** Saldos resultantes (null si el lado no aplica) o el motivo del rechazo. */
    private record Resultado(String motivo, BigDecimal saldoOrigen, BigDecimal saldoDestino) {
        static Resultado rechazo(String motivo) {
            return new Resultado(motivo, null, null);
        }
    }

    private final RepositorioCuentas cuentas;
    private final RepositorioOperaciones operaciones;
    private final RepositorioEventosProcesados eventos;
    private final Outbox outbox;
    private final JdbcClient jdbc;
    private final Clock reloj;

    public ServicioDePagos(RepositorioCuentas cuentas, RepositorioOperaciones operaciones,
                           RepositorioEventosProcesados eventos, Outbox outbox, JdbcClient jdbc, Clock reloj) {
        this.cuentas = cuentas;
        this.operaciones = operaciones;
        this.eventos = eventos;
        this.outbox = outbox;
        this.jdbc = jdbc;
        this.reloj = reloj;
    }

    @Transactional
    public void aplicar(EventoDePago pago) {
        LocalDateTime ahora = LocalDateTime.now(reloj).truncatedTo(ChronoUnit.SECONDS);
        if (eventos.existsById(pago.eventoId()) || yaAplicado(pago.pagoId())) {
            log.info("Pago {} ya aplicado (evento {} repetido): se ignora", pago.pagoId(), pago.eventoId());
            return;
        }
        eventos.save(new EventoProcesado(pago.eventoId(), pago.tipo(), ahora));

        Resultado resultado = switch (pago.operacion()) {
            case EventoDePago.TRANSFERENCIA -> transferir(pago, ahora);
            case EventoDePago.DEPOSITO -> depositar(pago, ahora);
            case EventoDePago.PAGO_SERVICIO -> pagarServicio(pago, ahora);
            default -> Resultado.rechazo("OPERACION_DESCONOCIDA");
        };

        Instant instante = reloj.instant();
        EventoDeMovimiento evento = resultado.motivo() == null
                ? EventoDeMovimiento.completada(pago, instante, resultado.saldoOrigen(), resultado.saldoDestino())
                : EventoDeMovimiento.rechazada(pago, instante, resultado.motivo());
        jdbc.sql("""
                        insert into cuentas.pago_aplicado (pago_id, operacion, cliente_id, cuenta_origen, cuenta_destino,
                            monto, resultado, motivo, aplicado_en)
                        values (:pago, :operacion, :cliente, :origen, :destino, :monto, :resultado, :motivo, :ahora)""")
                .param("pago", pago.pagoId()).param("operacion", operacionValida(pago.operacion()))
                .param("cliente", pago.clienteId()).param("origen", pago.cuentaOrigen())
                .param("destino", pago.cuentaDestino()).param("monto", pago.monto())
                .param("resultado", resultado.motivo() == null ? "COMPLETADA" : "RECHAZADA")
                .param("motivo", resultado.motivo()).param("ahora", ahora).update();
        outbox.registrar(Topicos.MOVIMIENTOS, evento.eventoId(), evento.clave(), evento.tipo(), evento);
        log.info("Pago {} {} {}: {}", pago.pagoId(), pago.operacion(), pago.monto(),
                resultado.motivo() == null ? "COMPLETADO" : "RECHAZADO " + resultado.motivo());
    }

    private Resultado transferir(EventoDePago pago, LocalDateTime ahora) {
        if (pago.cuentaOrigen() == null || pago.cuentaDestino() == null) {
            return Resultado.rechazo("DATOS_INCOMPLETOS");
        }
        if (Objects.equals(pago.cuentaOrigen(), pago.cuentaDestino())) {
            return Resultado.rechazo("MISMA_CUENTA");
        }
        // Siempre en orden de id: dos transferencias cruzadas (A->B y B->A) no se bloquean mutuamente.
        long primera = Math.min(pago.cuentaOrigen(), pago.cuentaDestino());
        long segunda = Math.max(pago.cuentaOrigen(), pago.cuentaDestino());
        Optional<Cuenta> a = cuentas.buscarParaActualizar(primera);
        Optional<Cuenta> b = cuentas.buscarParaActualizar(segunda);
        Optional<Cuenta> origen = pago.cuentaOrigen() == primera ? a : b;
        Optional<Cuenta> destino = pago.cuentaOrigen() == primera ? b : a;
        Optional<String> motivo = validarCargo(pago, origen);
        if (motivo.isPresent()) {
            return Resultado.rechazo(motivo.get());
        }
        if (destino.isEmpty()) {
            return Resultado.rechazo("CUENTA_DESTINO_NO_EXISTE");
        }
        if (!destino.get().estaActiva()) {
            return Resultado.rechazo("CUENTA_NO_OPERATIVA");
        }
        BigDecimal monto = pago.monto().setScale(2);
        origen.get().descontar(monto, ahora);
        destino.get().acreditar(monto, ahora);
        String huella = huella(pago);
        operaciones.save(Operacion.dePago(origen.get().getId(), Operacion.TIPO_TRANSFERENCIA_ENVIADA, monto,
                origen.get().getSaldoDisponible(), pago.pagoId(), "Transferencia a cuenta " + destino.get().getId(), huella, ahora));
        operaciones.save(Operacion.dePago(destino.get().getId(), Operacion.TIPO_TRANSFERENCIA_RECIBIDA, monto,
                destino.get().getSaldoDisponible(), pago.pagoId(), "Transferencia desde cuenta " + origen.get().getId(), huella, ahora));
        return new Resultado(null, origen.get().getSaldoDisponible(), destino.get().getSaldoDisponible());
    }

    private Resultado depositar(EventoDePago pago, LocalDateTime ahora) {
        if (pago.cuentaDestino() == null) {
            return Resultado.rechazo("DATOS_INCOMPLETOS");
        }
        if (!montoValido(pago.monto())) {
            return Resultado.rechazo("MONTO_INVALIDO");
        }
        Optional<Cuenta> destino = cuentas.buscarParaActualizar(pago.cuentaDestino());
        if (destino.isEmpty()) {
            return Resultado.rechazo("CUENTA_DESTINO_NO_EXISTE");
        }
        if (!destino.get().estaActiva()) {
            return Resultado.rechazo("CUENTA_NO_OPERATIVA");
        }
        BigDecimal monto = pago.monto().setScale(2);
        destino.get().acreditar(monto, ahora);
        operaciones.save(Operacion.dePago(destino.get().getId(), Operacion.TIPO_DEPOSITO, monto,
                destino.get().getSaldoDisponible(), pago.pagoId(), "Deposito", huella(pago), ahora));
        return new Resultado(null, null, destino.get().getSaldoDisponible());
    }

    private Resultado pagarServicio(EventoDePago pago, LocalDateTime ahora) {
        if (pago.cuentaOrigen() == null) {
            return Resultado.rechazo("DATOS_INCOMPLETOS");
        }
        Optional<Cuenta> origen = cuentas.buscarParaActualizar(pago.cuentaOrigen());
        Optional<String> motivo = validarCargo(pago, origen);
        if (motivo.isPresent()) {
            return Resultado.rechazo(motivo.get());
        }
        BigDecimal monto = pago.monto().setScale(2);
        origen.get().descontar(monto, ahora);
        operaciones.save(Operacion.dePago(origen.get().getId(), Operacion.TIPO_PAGO_SERVICIO, monto,
                origen.get().getSaldoDisponible(), pago.pagoId(), "Pago de servicio " + pago.convenio(), huella(pago), ahora));
        return new Resultado(null, origen.get().getSaldoDisponible(), null);
    }

    /** Reglas comunes a todo cargo: cuenta propia, operativa, de ahorro y con saldo. */
    private static Optional<String> validarCargo(EventoDePago pago, Optional<Cuenta> origen) {
        if (!montoValido(pago.monto())) {
            return Optional.of("MONTO_INVALIDO");
        }
        if (origen.isEmpty()) {
            return Optional.of("CUENTA_ORIGEN_NO_EXISTE");
        }
        Cuenta cuenta = origen.get();
        if (!Objects.equals(cuenta.getClienteId(), pago.clienteId())) {
            return Optional.of("CUENTA_AJENA");
        }
        if (!cuenta.estaActiva()) {
            return Optional.of("CUENTA_NO_OPERATIVA");
        }
        if (!cuenta.esAhorro()) {
            return Optional.of("CUENTA_NO_ADMITE_CARGOS");
        }
        if (cuenta.getSaldoDisponible().compareTo(pago.monto()) < 0) {
            return Optional.of("SALDO_INSUFICIENTE");
        }
        return Optional.empty();
    }

    private static boolean montoValido(BigDecimal monto) {
        return monto != null && monto.signum() > 0 && monto.stripTrailingZeros().scale() <= 0;
    }

    private boolean yaAplicado(String pagoId) {
        return jdbc.sql("select count(*) from cuentas.pago_aplicado where pago_id = :p").param("p", pagoId)
                .query(Long.class).single() > 0;
    }

    private static String operacionValida(String operacion) {
        return EventoDePago.TRANSFERENCIA.equals(operacion) || EventoDePago.DEPOSITO.equals(operacion)
                || EventoDePago.PAGO_SERVICIO.equals(operacion) ? operacion : EventoDePago.TRANSFERENCIA;
    }

    private static String huella(EventoDePago pago) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    (pago.pagoId() + "|" + pago.operacion() + "|" + pago.monto()).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
