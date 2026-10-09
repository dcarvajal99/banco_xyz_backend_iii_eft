package com.bancoxyz.pagos.evento;

import java.math.BigDecimal;
import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Resultado que publica cuentas-service en {@value Topicos#MOVIMIENTOS}. Los retiros en cajero llegan sin pagoId y aqui
 * se ignoran.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"version", "eventoId", "tipo"})
public record EventoDeMovimiento(int version, String eventoId, String tipo, String pagoId, Instant ocurridoEn,
                                 String operacion, Long clienteId, Long cuentaOrigen, Long cuentaDestino,
                                 BigDecimal monto, String moneda, String motivo, BigDecimal saldoOrigen,
                                 BigDecimal saldoDestino) {

    public static final int VERSION_ACTUAL = 1;
    public static final String COMPLETADA = "TransaccionCompletada";
    public static final String RECHAZADA = "TransaccionRechazada";

    /** Clave de Kafka: el cliente, para que sus movimientos lleguen en orden a quien los consume. */
    public String clave() {
        return clienteId == null ? "sin-cliente" : String.valueOf(clienteId);
    }
}
