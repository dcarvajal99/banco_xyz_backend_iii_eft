package com.bancoxyz.bff.web.seguridad;

import java.io.Serializable;

/** Identidad guardada en la sesion del servidor. El navegador nunca la ve: solo tiene la cookie de sesion. */
public record UsuarioWeb(long usuarioId, Long clienteId, String nombre, String rol) implements Serializable {

    public static final String EJECUTIVO = "EJECUTIVO";

    public boolean esEjecutivo() {
        return EJECUTIVO.equals(rol);
    }
}
