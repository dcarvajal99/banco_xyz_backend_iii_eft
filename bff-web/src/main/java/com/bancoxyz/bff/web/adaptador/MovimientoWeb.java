package com.bancoxyz.bff.web.adaptador;

import java.math.BigDecimal;
import java.time.LocalDate;

public record MovimientoWeb(String id, long cuentaId, LocalDate fecha, String tipo, String tipoEtiqueta, BigDecimal monto,
                            String descripcion, String origen, boolean anomalia, String observacion) {
}
