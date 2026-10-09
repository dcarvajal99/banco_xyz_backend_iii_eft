package com.bancoxyz.bff.movil.seguridad;

import org.springframework.security.oauth2.jwt.Jwt;

/** Lo que afirma un access token del canal movil. */
public record UsuarioMovil(long usuarioId, long clienteId, String nombre, String dispositivo, String familia) {

    public static UsuarioMovil desde(Jwt jwt) {
        return new UsuarioMovil(Long.parseLong(jwt.getSubject()), ((Number) jwt.getClaim("cliente")).longValue(),
                jwt.getClaimAsString("nombre"), jwt.getClaimAsString("dispositivo"), jwt.getClaimAsString("fam"));
    }
}
