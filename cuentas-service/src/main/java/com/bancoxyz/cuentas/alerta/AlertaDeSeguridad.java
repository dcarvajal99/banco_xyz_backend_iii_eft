package com.bancoxyz.cuentas.alerta;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Alerta de seguridad en {@code seguridad.alertas} (mismo contrato JSON que clientes-service y pagos-service).
 * Aqui: TarjetaBloqueada al tercer PIN incorrecto.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"version", "eventoId", "tipo"})
public record AlertaDeSeguridad(int version, String eventoId, String tipo, Instant ocurridoEn, String origen,
                                String severidad, Long clienteId, Long usuarioId, Long cuentaId, String referencia,
                                String detalle) {

    public static final String TARJETA_BLOQUEADA = "TarjetaBloqueada";

    public static AlertaDeSeguridad tarjetaBloqueada(Long clienteId, long cuentaId, String ultimos4, Instant ahora) {
        return new AlertaDeSeguridad(1, UUID.randomUUID().toString(), TARJETA_BLOQUEADA, ahora, "cuentas-service", "ALTA",
                clienteId, null, cuentaId, "****" + ultimos4, "Tarjeta bloqueada por 3 PIN incorrectos en cajero");
    }

    public String clave() {
        return clienteId == null ? "sin-cliente" : String.valueOf(clienteId);
    }
}
