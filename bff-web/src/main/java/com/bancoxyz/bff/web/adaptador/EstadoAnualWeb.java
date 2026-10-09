package com.bancoxyz.bff.web.adaptador;

import java.math.BigDecimal;
import java.time.LocalDate;

public record EstadoAnualWeb(long cuentaId, int anio, long cantidadMovimientos, BigDecimal totalDepositos,
                             BigDecimal totalCargos, BigDecimal saldoNeto, LocalDate primeraFecha, LocalDate ultimaFecha,
                             long movimientosConAnomalia) {
}
