package com.bancoxyz.pagos.pago;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Cuerpos de entrada de cada operacion: un DTO por endpoint, validado antes de entrar al servicio. */
public final class Solicitudes {

    private Solicitudes() {
    }

    public record Transferencia(@NotNull @Positive Long cuentaOrigen, @NotNull @Positive Long cuentaDestino,
                                @NotNull @Positive @Digits(integer = 12, fraction = 0) @DecimalMax("100000000") BigDecimal monto) {
    }

    public record Deposito(@NotNull @Positive Long cuentaDestino,
                           @NotNull @Positive @Digits(integer = 12, fraction = 0) @DecimalMax("100000000") BigDecimal monto) {
    }

    /** @param convenio empresa de servicios (luz, agua, telefonia...) */
    public record PagoServicio(@NotNull @Positive Long cuentaOrigen, @NotBlank @Size(max = 40) String convenio,
                               @NotNull @Positive @Digits(integer = 12, fraction = 0) @DecimalMax("100000000") BigDecimal monto) {
    }
}
