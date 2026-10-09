package com.bancoxyz.bff.web.core;

import java.math.BigDecimal;
import java.time.LocalDate;

public record MovimientoCore(String id, long cuentaId, LocalDate fecha, String tipo, BigDecimal monto, String descripcion,
                             String origen, boolean anomalia, String observacion) {
}
