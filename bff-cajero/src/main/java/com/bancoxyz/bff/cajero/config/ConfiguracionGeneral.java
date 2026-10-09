package com.bancoxyz.bff.cajero.config;

import java.time.Clock;
import java.time.ZoneId;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ConfiguracionGeneral {

    /** Reloj inyectable: la ventana de la firma y la vida del token se prueban con un reloj fijo. */
    @Bean
    public Clock reloj() {
        return Clock.system(ZoneId.of("America/Santiago"));
    }

    @Bean
    public OpenAPI contratoOpenApi() {
        return new OpenAPI()
                .info(new Info().title("Banco XYZ - BFF Cajero Automatico").version("1.0.0")
                        .description("Backend para los cajeros automaticos: respuestas minimas orientadas a la pantalla "
                                + "y al dispensador. Cada solicitud va firmada por el terminal (HMAC-SHA256 sobre "
                                + "METODO, RUTA, X-Marca-Tiempo, X-Nonce, Idempotency-Key y el hash del cuerpo) y las "
                                + "operaciones exigen ademas el token de sesion de 2 minutos obtenido con tarjeta y PIN."))
                .components(new Components()
                        .addSecuritySchemes("firmaDelTerminal", new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER).name("X-Firma")
                                .description("Junto con X-Terminal-Id, X-Marca-Tiempo y X-Nonce"))
                        .addSecuritySchemes("sesionDeTarjeta", new SecurityScheme().type(SecurityScheme.Type.HTTP)
                                .scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("firmaDelTerminal").addList("sesionDeTarjeta"));
    }
}
