package com.bancoxyz.bff.cajero.core;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** DTO propio del contrato operacion-retiro.json. */
public record OperacionCore(long operacionId, long cuentaId, Long tarjetaId, String tipo, String canal,
                            BigDecimal monto, BigDecimal saldoResultante, String terminalId, LocalDateTime fechaHora) {
}
