package com.bancoxyz.bff.movil.core;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** DTO propio del contrato cuenta.json. El movil usa id, tipo y saldo, pero lee la forma completa. */
public record CuentaCore(long cuentaId, Long clienteId, String titular, String tipo, BigDecimal saldoDisponible,
                         BigDecimal saldoCierre, BigDecimal tasaMensual, BigDecimal interesCierre, boolean anomalia,
                         String observacion, long cierreJobExecutionId, LocalDateTime actualizadaEn) {
}
