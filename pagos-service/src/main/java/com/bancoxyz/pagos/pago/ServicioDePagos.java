package com.bancoxyz.pagos.pago;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.bancoxyz.pagos.cuentas.ClienteDeCuentas;
import com.bancoxyz.pagos.cuentas.Validacion;
import com.bancoxyz.pagos.error.Errores;
import com.bancoxyz.pagos.evento.AlertaDeSeguridad;
import com.bancoxyz.pagos.evento.EventoDeMovimiento;
import com.bancoxyz.pagos.evento.EventoDePago;
import com.bancoxyz.pagos.evento.EventoProcesado;
import com.bancoxyz.pagos.evento.RepositorioEventosProcesados;
import com.bancoxyz.pagos.evento.Topicos;
import com.bancoxyz.pagos.outbox.Outbox;
import com.bancoxyz.pagos.seguridad.IdentidadDelToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Registro de pagos y seguimiento de su resultado.
 *
 * <ol>
 *   <li>Prevalida la cuenta en cuentas-service FUERA de la transaccion (no se retiene una conexion esperando a otro
 *       servicio); si no responde, la validacion queda DIFERIDA.</li>
 *   <li>Guarda el pago y evalua las reglas de fraude. Si alguna aplica: RECHAZADO + alerta OperacionSospechosa. Si no:
 *       SOLICITADO + evento PagoSolicitado. Pago y evento quedan en la misma transaccion (outbox).</li>
 *   <li>{@link #registrarResultado}: cuentas-service informa COMPLETADA o RECHAZADA y el pago llega a su estado final.</li>
 * </ol>
 */
@Service
public class ServicioDePagos {

    private static final Logger log = LoggerFactory.getLogger(ServicioDePagos.class);

    /** El pago y si la solicitud era un reintento con la misma Idempotency-Key. */
    public record Resultado(PagoDto pago, boolean repetido) {
    }

    /** Datos comunes de las tres operaciones. */
    public record Solicitud(String operacion, Long cuentaOrigen, Long cuentaDestino, String convenio, BigDecimal monto) {
    }

    private final RepositorioPagos pagos;
    private final RepositorioHistorial historial;
    private final RepositorioEventosProcesados procesados;
    private final ClienteDeCuentas cuentas;
    private final ReglasDeFraude fraude;
    private final Outbox outbox;
    private final TransactionTemplate transaccion;
    private final Clock reloj;

    public ServicioDePagos(RepositorioPagos pagos, RepositorioHistorial historial, RepositorioEventosProcesados procesados,
                           ClienteDeCuentas cuentas, ReglasDeFraude fraude, Outbox outbox, TransactionTemplate transaccion,
                           Clock reloj) {
        this.pagos = pagos;
        this.historial = historial;
        this.procesados = procesados;
        this.cuentas = cuentas;
        this.fraude = fraude;
        this.outbox = outbox;
        this.transaccion = transaccion;
        this.reloj = reloj;
    }

    public Resultado solicitar(IdentidadDelToken quien, Solicitud solicitud, String clave, String canal) {
        if (!quien.esCliente()) {
            throw Errores.accesoDenegado();
        }
        Optional<Resultado> previo = repetido(quien, solicitud, clave);
        if (previo.isPresent()) {
            return previo.get();
        }
        if (solicitud.cuentaOrigen() != null && Objects.equals(solicitud.cuentaOrigen(), solicitud.cuentaDestino())) {
            throw Errores.mismaCuenta();
        }
        // El deposito va a una cuenta propia: se valida la de destino con la misma consulta.
        Long aValidar = solicitud.cuentaOrigen() != null ? solicitud.cuentaOrigen() : solicitud.cuentaDestino();
        Validacion validacion = cuentas.validarCuentaDeOrigen(quien.clienteId(), aValidar);

        return transaccion.execute(estado -> {
            Optional<Resultado> carrera = repetido(quien, solicitud, clave);
            if (carrera.isPresent()) {
                return carrera.get();
            }
            LocalDateTime ahora = ahora();
            Pago pago = pagos.save(new Pago(UUID.randomUUID().toString(), solicitud.operacion(), quien.clienteId(),
                    quien.usuarioId(), solicitud.cuentaOrigen(), solicitud.cuentaDestino(), solicitud.convenio(),
                    solicitud.monto(), validacion.name(), canal, clave, ahora));
            Optional<String> regla = fraude.evaluar(pago);
            if (regla.isPresent()) {
                pago.finalizar(Pago.Estado.RECHAZADO, regla.get(), ahora);
                AlertaDeSeguridad alerta = AlertaDeSeguridad.operacionSospechosa(pago, regla.get(), reloj.instant());
                outbox.registrar(Topicos.SEGURIDAD_ALERTAS, alerta.eventoId(), alerta.clave(), alerta.tipo(), alerta);
                historial.save(new PasoDelHistorial(pago.getId(), "RechazadoPorFraude", regla.get(), ahora));
                log.warn("Pago {} rechazado por la regla de fraude {}: alerta OperacionSospechosa", corto(pago.getId()), regla.get());
            } else {
                EventoDePago evento = EventoDePago.solicitado(pago, reloj.instant());
                outbox.registrar(Topicos.PAGOS_SOLICITADOS, evento.eventoId(), evento.clave(), evento.tipo(), evento);
                historial.save(new PasoDelHistorial(pago.getId(), EventoDePago.SOLICITADO, "validacion " + validacion, ahora));
                log.info("Pago {} {} ${} solicitado (validacion {})", corto(pago.getId()), pago.getOperacion(),
                        pago.getMonto().toBigInteger(), validacion);
            }
            return new Resultado(dto(pago), false);
        });
    }

    @Transactional(readOnly = true)
    public PagoDto consultar(IdentidadDelToken quien, String id) {
        return pagos.findById(id).filter(p -> Objects.equals(p.getClienteId(), quien.clienteId())).map(this::dto)
                .orElseThrow(Errores::pagoNoEncontrado);
    }

    @Transactional(readOnly = true)
    public List<PagoDto> recientes(IdentidadDelToken quien) {
        if (!quien.esCliente()) {
            throw Errores.accesoDenegado();
        }
        return pagos.findTop20ByClienteIdOrderByCreadoEnDesc(quien.clienteId()).stream().map(this::dto).toList();
    }

    /** Resultado de cuentas-service. Idempotente: un evento repetido o atrasado no cambia nada. */
    @Transactional
    public void registrarResultado(EventoDeMovimiento evento) {
        if (evento.pagoId() == null || procesados.existsById(evento.eventoId())) {
            return;
        }
        procesados.save(new EventoProcesado(evento.eventoId(), evento.tipo(), ahora()));
        Pago.Estado nuevo = EventoDeMovimiento.COMPLETADA.equals(evento.tipo()) ? Pago.Estado.COMPLETADO
                : EventoDeMovimiento.RECHAZADA.equals(evento.tipo()) ? Pago.Estado.RECHAZADO : null;
        Optional<Pago> pago = nuevo == null ? Optional.empty() : pagos.buscarParaActualizar(evento.pagoId());
        if (pago.isEmpty()) {
            return;
        }
        boolean cambio = pago.get().finalizar(nuevo, evento.motivo(), ahora());
        historial.save(new PasoDelHistorial(pago.get().getId(), evento.tipo(),
                (evento.motivo() == null ? "" : evento.motivo()) + (cambio ? "" : " (llego tarde: no cambia)"), ahora()));
        log.info("Pago {} -> {}{}", corto(pago.get().getId()), pago.get().getEstado(),
                evento.motivo() == null ? "" : " " + evento.motivo());
    }

    private Optional<Resultado> repetido(IdentidadDelToken quien, Solicitud s, String clave) {
        return pagos.findByClienteIdAndClaveIdempotencia(quien.clienteId(), clave).map(previo -> {
            if (!previo.mismaSolicitud(s.operacion(), s.cuentaOrigen(), s.cuentaDestino(), s.convenio(), s.monto())) {
                throw Errores.idempotenciaConflicto();
            }
            return new Resultado(dto(previo), true);
        });
    }

    private PagoDto dto(Pago pago) {
        return PagoDto.desde(pago, historial.findByPagoIdOrderByIdAsc(pago.getId()));
    }

    private static String corto(String id) {
        return id.substring(0, Math.min(8, id.length()));
    }

    private LocalDateTime ahora() {
        return LocalDateTime.now(reloj).truncatedTo(ChronoUnit.MILLIS);
    }
}
