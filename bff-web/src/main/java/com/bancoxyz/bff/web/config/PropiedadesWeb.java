package com.bancoxyz.bff.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "banco.web")
public record PropiedadesWeb(Agregador agregador) {

    public record Agregador(int hilos, int cola, long timeoutSegundos) {
    }
}
