package com.bancoxyz.bff.cajero.sesion;

import org.springframework.security.oauth2.jwt.Jwt;

/** Lo que el token de sesion afirma: que tarjeta, que cuenta y en que terminal. */
public record SesionDeTarjeta(long tarjetaId, long cuentaId, String terminal) {

    public static SesionDeTarjeta desde(Jwt jwt) {
        Object cuenta = jwt.getClaim("cuenta");
        return new SesionDeTarjeta(Long.parseLong(jwt.getSubject()), ((Number) cuenta).longValue(),
                jwt.getClaimAsString("terminal"));
    }
}
