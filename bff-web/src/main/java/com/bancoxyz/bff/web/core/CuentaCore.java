package com.bancoxyz.bff.web.core;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** DTO propio del contrato cuenta.json. La web la usa completa. */
public record CuentaCore(long cuentaId, Long clienteId, String titular, String tipo, BigDecimal saldoDisponible,
                         BigDecimal saldoCierre, BigDecimal tasaMensual, BigDecimal interesCierre, boolean anomalia,
                         String observacion, long cierreJobExecutionId, LocalDateTime actualizadaEn) {
}
