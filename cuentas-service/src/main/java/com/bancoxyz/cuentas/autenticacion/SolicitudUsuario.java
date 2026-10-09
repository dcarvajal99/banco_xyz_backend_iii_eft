package com.bancoxyz.cuentas.autenticacion;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SolicitudUsuario(@NotBlank @Size(max = 60) String usuario, @NotBlank @Size(max = 200) String password) {
}
