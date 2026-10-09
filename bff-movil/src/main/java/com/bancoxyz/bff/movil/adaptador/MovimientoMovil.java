package com.bancoxyz.bff.movil.adaptador;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Tres campos: lo que cabe en una fila de la pantalla del telefono. */
public record MovimientoMovil(LocalDate fecha, String desc, BigDecimal monto) {
}
