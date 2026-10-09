package com.bancoxyz.bff.movil.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "banco.movil")
public record PropiedadesMovil(Jwt jwt, long accessSegundos, long refreshDias, long cacheCuentasSegundos,
                               @DefaultValue("8") int hilosPortada) {

    public record Jwt(String secreto) {
    }
}
