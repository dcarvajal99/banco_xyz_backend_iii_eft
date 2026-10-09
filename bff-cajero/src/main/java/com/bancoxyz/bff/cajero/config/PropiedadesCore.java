package com.bancoxyz.bff.cajero.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Donde esta cuentas-service. La credencial es un token client_credentials (ver TokenDeServicio), no una clave. */
@ConfigurationProperties(prefix = "banco.core")
public record PropiedadesCore(String url) {
}
