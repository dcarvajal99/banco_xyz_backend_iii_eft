package com.bancoxyz.clientes.cliente;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Cambio de los datos de contacto. Un campo vacio borra el dato; los tres viajan siempre (PUT reemplaza el perfil). */
public record SolicitudPerfil(@Email @Size(max = 120) String email,
                              @Pattern(regexp = "^$|^\\+?[0-9 ]{8,15}$", message = "debe tener entre 8 y 15 digitos")
                              String telefono,
                              @Size(max = 200) String direccion) {

    Cliente.Perfil comoPerfil() {
        return new Cliente.Perfil(vacioANulo(email), vacioANulo(telefono), vacioANulo(direccion));
    }

    private static String vacioANulo(String valor) {
        return valor == null || valor.isBlank() ? null : valor.trim();
    }
}
