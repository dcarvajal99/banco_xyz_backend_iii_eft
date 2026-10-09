package com.bancoxyz.bff.web.adaptador;

import java.math.BigDecimal;

/** La cuenta completa, con etiquetas listas para mostrar. */
public record CuentaWeb(long id, String tipo, String tipoEtiqueta, BigDecimal saldoDisponible, BigDecimal saldoCierre,
                        BigDecimal tasaMensual, String tasaMensualPorcentaje, BigDecimal interesCierre, boolean anomalia,
                        String observacion) {
}
