package com.bancoxyz.bff.movil.seguridad;

import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

public final class AccesoDePrueba {

    private AccesoDePrueba() {
    }

    public static OAuth2TokenValidator<Jwt> azp() {
        return ConfiguracionSeguridad.claim("azp", "banca-movil");
    }
}
