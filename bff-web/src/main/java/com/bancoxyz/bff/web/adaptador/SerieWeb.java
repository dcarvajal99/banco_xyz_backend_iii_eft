package com.bancoxyz.bff.web.adaptador;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Columnas paralelas, el formato que esperan las librerias de graficos: el navegador no tiene que pivotear nada. */
public record SerieWeb(List<LocalDate> fechas, List<BigDecimal> debitos, List<BigDecimal> creditos, List<Long> anomalias) {
}
