package com.bancoxyz.bff.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Donde esta el core y con que credencial de servicio entra este BFF. */
@ConfigurationProperties(prefix = "banco.core")
public record PropiedadesCore(String url, String usuario, String clave) {
}
