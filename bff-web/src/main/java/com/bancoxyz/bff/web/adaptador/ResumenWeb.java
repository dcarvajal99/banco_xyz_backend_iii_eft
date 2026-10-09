package com.bancoxyz.bff.web.adaptador;

import java.math.BigDecimal;

public record ResumenWeb(BigDecimal totalAhorro, BigDecimal totalDeuda, int cantidadCuentas, long cuentasConAnomalia) {
}
