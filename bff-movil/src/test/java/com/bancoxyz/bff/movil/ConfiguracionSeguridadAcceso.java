package com.bancoxyz.bff.movil;

import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

/** Acceso de prueba al validador de azp (paquete distinto al de la configuracion). */
final class ConfiguracionSeguridadAcceso {

    private ConfiguracionSeguridadAcceso() {
    }

    static OAuth2TokenValidator<Jwt> azp() {
        return com.bancoxyz.bff.movil.seguridad.AccesoDePrueba.azp();
    }
}
