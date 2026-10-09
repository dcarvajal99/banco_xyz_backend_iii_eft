package com.bancoxyz.cuentas.pago;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * PagoSolicitado, tal como lo publica pagos-service en {@value Topicos#PAGOS_SOLICITADOS}.
 * Cada servicio tiene su copia del registro: el contrato es el JSON (README general).
 *
 * @param operacion TRANSFERENCIA (origen -> destino), DEPOSITO (-> destino) o PAGO_SERVICIO (origen -> convenio)
 */
public record EventoDePago(int version, String eventoId, String tipo, String pagoId, Instant ocurridoEn,
                           String operacion, Long clienteId, Long usuarioId, Long cuentaOrigen, Long cuentaDestino,
                           BigDecimal monto, String moneda, String convenio) {

    public static final int VERSION_ACTUAL = 1;
    public static final String SOLICITADO = "PagoSolicitado";
    public static final String TRANSFERENCIA = "TRANSFERENCIA";
    public static final String DEPOSITO = "DEPOSITO";
    public static final String PAGO_SERVICIO = "PAGO_SERVICIO";
}
