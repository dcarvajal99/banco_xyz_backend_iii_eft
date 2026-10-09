package com.bancoxyz.clientes.cliente;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Alta de un cliente nuevo por un ejecutivo, con sus credenciales iniciales.
 *
 * @param usuario     nombre de usuario (minusculas, numeros y puntos)
 * @param claveInicial clave que el cliente usara en su primer ingreso; minimo 10 caracteres
 */
public record SolicitudAltaCliente(@NotBlank @Size(max = 120) String nombre,
                                   @NotNull @Min(18) @Max(110) Integer edad,
                                   @Valid @NotNull SolicitudPerfil perfil,
                                   @NotBlank @Pattern(regexp = "^[a-z0-9.]{4,60}$",
                                           message = "solo minusculas, numeros y puntos (4 a 60)") String usuario,
                                   @NotBlank @Size(min = 10, max = 200) String claveInicial) {
}
