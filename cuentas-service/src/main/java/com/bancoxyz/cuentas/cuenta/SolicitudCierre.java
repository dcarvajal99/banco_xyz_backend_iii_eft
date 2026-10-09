package com.bancoxyz.cuentas.cuenta;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SolicitudCierre(@NotBlank @Size(max = 120) String motivo) {
}
