package com.bancoxyz.bff.web.core;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ResumenDiarioCore(LocalDate fecha, long cantidadTransacciones, BigDecimal totalDebitos,
                                BigDecimal totalCreditos, BigDecimal montoMaximo, long cantidadAnomalias,
                                long jobExecutionId) {
}
