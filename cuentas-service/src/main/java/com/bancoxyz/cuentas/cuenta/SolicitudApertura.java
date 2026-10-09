package com.bancoxyz.cuentas.cuenta;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Apertura de una cuenta para un cliente existente. Parte con saldo cero: los fondos llegan por pagos-service. */
public record SolicitudApertura(@NotNull @Positive Long clienteId,
                                @NotNull @Pattern(regexp = "ahorro|prestamo|hipoteca") String tipo,
                                @Size(max = 40) String alias) {
}
