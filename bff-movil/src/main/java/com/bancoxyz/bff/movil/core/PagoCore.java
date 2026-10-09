package com.bancoxyz.bff.movil.core;

import java.math.BigDecimal;

/** Lo que la app necesita de un pago de pagos-service (el resto del DTO se ignora). */
public record PagoCore(String id, String operacion, String estado, String motivo, BigDecimal monto) {
}
