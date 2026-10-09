package com.bancoxyz.pagos.evento;

import java.time.Instant;
import java.util.UUID;

import com.bancoxyz.pagos.pago.Pago;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** Alerta en {@code seguridad.alertas} (mismo contrato JSON en todo el banco). Aqui: OperacionSospechosa. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"version", "eventoId", "tipo"})
public record AlertaDeSeguridad(int version, String eventoId, String tipo, Instant ocurridoEn, String origen,
                                String severidad, Long clienteId, Long usuarioId, Long cuentaId, String referencia,
                                String detalle) {

    public static final String OPERACION_SOSPECHOSA = "OperacionSospechosa";

    public static AlertaDeSeguridad operacionSospechosa(Pago pago, String regla, Instant ahora) {
        return new AlertaDeSeguridad(1, UUID.randomUUID().toString(), OPERACION_SOSPECHOSA, ahora, "pagos-service",
                "ALTA", pago.getClienteId(), pago.getUsuarioId(),
                pago.getCuentaOrigen() != null ? pago.getCuentaOrigen() : pago.getCuentaDestino(), pago.getId(),
                pago.getOperacion() + " de $" + pago.getMonto().toBigInteger() + " rechazada por la regla " + regla);
    }

    public String clave() {
        return String.valueOf(clienteId);
    }
}
