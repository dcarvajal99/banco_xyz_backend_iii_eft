package com.bancoxyz.bff.movil.seguridad;

import org.springframework.security.oauth2.jwt.Jwt;

/** El cliente que usa la app, segun el token de banco-auth (claims firmados, no datos que mande la app). */
public record UsuarioMovil(long usuarioId, long clienteId, String nombre) {

    public static UsuarioMovil desde(Jwt jwt) {
        return new UsuarioMovil(((Number) jwt.getClaim("usuario_id")).longValue(),
                ((Number) jwt.getClaim("cliente_id")).longValue(), nombreVisible(jwt.getSubject()));
    }

    /** "diana.prince" -> "Diana Prince": el saludo de la portada sin pedir la ficha completa. */
    static String nombreVisible(String usuario) {
        StringBuilder nombre = new StringBuilder();
        for (String parte : usuario.split("\\.")) {
            if (!parte.isEmpty()) {
                nombre.append(nombre.isEmpty() ? "" : " ").append(Character.toUpperCase(parte.charAt(0))).append(parte.substring(1));
            }
        }
        return nombre.toString();
    }
}
