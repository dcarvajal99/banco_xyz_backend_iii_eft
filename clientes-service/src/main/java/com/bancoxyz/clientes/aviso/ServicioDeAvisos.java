package com.bancoxyz.clientes.aviso;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.bancoxyz.clientes.seguridad.IdentidadDelToken;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Traduce los eventos del banco a avisos legibles para el cliente. Idempotente por eventoId (Kafka entrega al menos
 * una vez). Un evento sin cliente (cuenta sin titular identificado) no genera aviso.
 */
@Service
public class ServicioDeAvisos {

    private static final Logger log = LoggerFactory.getLogger(ServicioDeAvisos.class);
    private static final Map<String, String> MOTIVOS = Map.of(
            "SALDO_INSUFICIENTE", "saldo insuficiente",
            "CUENTA_AJENA", "la cuenta de origen no es suya",
            "CUENTA_NO_OPERATIVA", "la cuenta esta bloqueada o cerrada",
            "CUENTA_DESTINO_NO_EXISTE", "la cuenta de destino no existe",
            "CUENTA_NO_ADMITE_CARGOS", "esa cuenta no admite cargos");

    private final RepositorioAvisos avisos;
    private final Clock reloj;

    public ServicioDeAvisos(RepositorioAvisos avisos, Clock reloj) {
        this.avisos = avisos;
        this.reloj = reloj;
    }

    /** TransaccionCompletada / TransaccionRechazada de cuentas-service. */
    @Transactional
    public void movimiento(JsonNode e) {
        String operacion = texto(e, "operacion");
        String monto = pesos(e.path("monto").decimalValue());
        boolean completada = "TransaccionCompletada".equals(texto(e, "tipo"));
        String detalle = switch (operacion) {
            case "TRANSFERENCIA" -> "Transferencia de " + monto + " desde la cuenta " + texto(e, "cuentaOrigen")
                    + " a la " + texto(e, "cuentaDestino");
            case "DEPOSITO" -> "Deposito de " + monto + " en la cuenta " + texto(e, "cuentaDestino");
            case "PAGO_SERVICIO" -> "Pago de servicio por " + monto + " desde la cuenta " + texto(e, "cuentaOrigen");
            case "RETIRO" -> "Retiro en cajero de " + monto + " de la cuenta " + texto(e, "cuentaOrigen");
            default -> operacion + " por " + monto;
        };
        String mensaje = completada ? detalle + ". Saldo disponible: " + saldo(e)
                : detalle + " rechazada: " + MOTIVOS.getOrDefault(texto(e, "motivo"), texto(e, "motivo"));
        guardar(e, completada ? "Transaccion completada" : "Transaccion rechazada", mensaje, false);
    }

    /** Alerta de seguridad (usuario bloqueado, tarjeta bloqueada, operacion sospechosa). */
    @Transactional
    public void alerta(JsonNode e) {
        String titulo = switch (texto(e, "tipo")) {
            case "UsuarioBloqueado" -> "Alerta: usuario bloqueado";
            case "TarjetaBloqueada" -> "Alerta: tarjeta bloqueada";
            case "OperacionSospechosa" -> "Alerta: operacion detenida por seguridad";
            default -> "Alerta de seguridad";
        };
        guardar(e, titulo, texto(e, "detalle") + ". Si no fue usted, llame al banco.", true);
    }

    @Transactional(readOnly = true)
    public List<AvisoDto> deCliente(IdentidadDelToken quien, long clienteId) {
        quien.exigirClienteVisible(clienteId);
        return avisos.findTop30ByClienteIdOrderByIdDesc(clienteId).stream().map(AvisoDto::de).toList();
    }

    private void guardar(JsonNode e, String titulo, String mensaje, boolean seguridad) {
        if (!e.hasNonNull("clienteId") || avisos.existsByEventoId(texto(e, "eventoId"))) {
            return;
        }
        avisos.save(new Aviso(e.get("clienteId").asLong(), texto(e, "eventoId"), texto(e, "tipo"), titulo, mensaje,
                seguridad, LocalDateTime.now(reloj)));
        log.info("Aviso al cliente {}: {} | {}", e.get("clienteId").asLong(), titulo, mensaje);
    }

    private static String saldo(JsonNode e) {
        JsonNode saldo = e.hasNonNull("saldoOrigen") ? e.get("saldoOrigen") : e.path("saldoDestino");
        return saldo.isMissingNode() || saldo.isNull() ? "-" : pesos(saldo.decimalValue());
    }

    private static String texto(JsonNode e, String campo) {
        return e.path(campo).asText("");
    }

    private static String pesos(BigDecimal monto) {
        return "$" + NumberFormat.getIntegerInstance(Locale.forLanguageTag("es-CL")).format(monto);
    }
}
