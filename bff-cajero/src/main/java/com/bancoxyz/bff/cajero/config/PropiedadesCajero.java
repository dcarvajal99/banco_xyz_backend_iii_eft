package com.bancoxyz.bff.cajero.config;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "banco.cajero")
public record PropiedadesCajero(Jwt jwt, List<Terminal> terminales, BigDecimal multiploBilletes,
                                BigDecimal maximoPorRetiro, long ventanaFirmaSegundos, long sesionSegundos) {

    public record Jwt(String secreto) {
    }

    /** Terminal autorizado. El secreto lo comparte solo ese cajero y este BFF. */
    public record Terminal(String id, String secreto, String ubicacion) {
    }
}
