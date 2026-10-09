package com.bancoxyz.bff.cajero.operacion;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Lo que imprime el cajero y la orden para el dispensador. */
public record ComprobanteRetiro(String comprobante, BigDecimal monto, BigDecimal saldoRestante, LocalDateTime fechaHora,
                                String cuenta, String accion) {
}
