package com.bancoxyz.bff.movil.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Propiedades del BFF movil bajo {@code banco.movil}.
 *
 * @param clienteOAuth         unico cliente OAuth cuyos tokens acepta este BFF (claim {@code azp}): la app movil
 * @param pagosUrl             base de la API de pagos-service
 * @param cacheCuentasSegundos vida de la copia de cuentas y movimientos (ahorra llamadas en recargas seguidas)
 * @param hilosPortada         consultas en paralelo de la portada (0 = en serie, solo para medir)
 */
@ConfigurationProperties(prefix = "banco.movil")
public record PropiedadesMovil(@DefaultValue("banca-movil") String clienteOAuth, String pagosUrl,
                               long cacheCuentasSegundos, @DefaultValue("8") int hilosPortada) {
}
