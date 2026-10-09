package com.bancoxyz.bff.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Donde estan los microservicios ({@code https://<servicio>/api/v1} con descubrimiento por Eureka).
 *
 * @param urlTrasSalir a donde vuelve el navegador al cerrar sesion
 */
@ConfigurationProperties(prefix = "banco.web")
public record PropiedadesWeb(String cuentasUrl, String clientesUrl, String pagosUrl, String urlTrasSalir) {
}
