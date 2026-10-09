package com.bancoxyz.cuentas.pago;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Transaccion completada o rechazada, en {@value Topicos#MOVIMIENTOS}. La consumen pagos-service (estado del pago) y
 * clientes-service (aviso al cliente). Un retiro en cajero tambien se publica, sin pagoId.
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

    public static EventoDeMovimiento completada(EventoDePago pago, Instant ahora, BigDecimal saldoOrigen,
                                                BigDecimal saldoDestino) {
        return new EventoDeMovimiento(VERSION_ACTUAL, UUID.randomUUID().toString(), COMPLETADA, pago.pagoId(), ahora,
                pago.operacion(), pago.clienteId(), pago.cuentaOrigen(), pago.cuentaDestino(), pago.monto(), "CLP", null,
                saldoOrigen, saldoDestino);
    }

    public static EventoDeMovimiento rechazada(EventoDePago pago, Instant ahora, String motivo) {
        return new EventoDeMovimiento(VERSION_ACTUAL, UUID.randomUUID().toString(), RECHAZADA, pago.pagoId(), ahora,
                pago.operacion(), pago.clienteId(), pago.cuentaOrigen(), pago.cuentaDestino(), pago.monto(), "CLP",
                motivo, null, null);
    }

    public static EventoDeMovimiento retiro(long clienteId, long cuentaId, BigDecimal monto, BigDecimal saldo, Instant ahora) {
        return new EventoDeMovimiento(VERSION_ACTUAL, UUID.randomUUID().toString(), COMPLETADA, null, ahora, "RETIRO",
                clienteId, cuentaId, null, monto, "CLP", null, saldo, null);
    }

    /** Clave de Kafka: el cliente, para que sus movimientos lleguen en orden a quien los consume. */
    public String clave() {
        return clienteId == null ? "sin-cliente" : String.valueOf(clienteId);
    }
}
