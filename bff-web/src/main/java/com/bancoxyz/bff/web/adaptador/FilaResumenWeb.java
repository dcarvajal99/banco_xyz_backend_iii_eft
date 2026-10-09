package com.bancoxyz.bff.web.adaptador;

import java.math.BigDecimal;
import java.time.LocalDate;

public record FilaResumenWeb(LocalDate fecha, long cantidadTransacciones, BigDecimal totalDebitos, BigDecimal totalCreditos,
                             BigDecimal montoMaximo, long cantidadAnomalias) {
}
