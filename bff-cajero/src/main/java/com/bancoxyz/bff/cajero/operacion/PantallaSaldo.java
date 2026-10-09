package com.bancoxyz.bff.cajero.operacion;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PantallaSaldo(BigDecimal saldoDisponible, String moneda, String mensajePantalla, LocalDateTime fechaHora) {
}
