package com.bancoxyz.pagos.evento;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.bancoxyz.pagos.pago.Pago;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** PagoSolicitado en {@value Topicos#PAGOS_SOLICITADOS}. Clave de Kafka: el cliente (sus pagos, en orden). */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"version", "eventoId", "tipo"})
public record EventoDePago(int version, String eventoId, String tipo, String pagoId, Instant ocurridoEn,
                           String operacion, Long clienteId, Long usuarioId, Long cuentaOrigen, Long cuentaDestino,
                           BigDecimal monto, String moneda, String convenio, String canal) {

    public static final int VERSION_ACTUAL = 1;
    public static final String SOLICITADO = "PagoSolicitado";

    public static EventoDePago solicitado(Pago pago, Instant ahora) {
        return new EventoDePago(VERSION_ACTUAL, UUID.randomUUID().toString(), SOLICITADO, pago.getId(), ahora,
                pago.getOperacion(), pago.getClienteId(), pago.getUsuarioId(), pago.getCuentaOrigen(),
                pago.getCuentaDestino(), pago.getMonto(), "CLP", pago.getConvenio(), pago.getCanal());
    }

    public String clave() {
        return String.valueOf(clienteId);
    }
}
