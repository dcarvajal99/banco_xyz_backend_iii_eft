package com.bancoxyz.pagos.config;

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

    /**
     * Un unico reloj con la zona del banco.
     *
     * <p>El limite diario de retiro depende de cuando empieza "hoy". Si cada clase llamara a
     * {@code LocalDateTime.now()} sin zona, en un servidor con TZ=UTC un retiro de las 22:00 en
     * Santiago quedaria guardado al dia siguiente y contaria dos veces. Todas las marcas de tiempo
     * salen de este reloj, y las pruebas lo reemplazan por uno fijo.</p>
     */
    @Bean
    public Clock reloj(PropiedadesPagos propiedades) {
        return Clock.system(ZoneId.of(propiedades.zonaHoraria()));
    }

    @Bean
    public OpenAPI contratoOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Banco XYZ - pagos-service")
                        .version("1.0.0")
                        .description("Procesamiento de pagos: transferencias, depositos y pagos de servicios. Responde 202 "
                                + "Accepted: cuentas-service aplica el movimiento por Kafka y el estado final se consulta "
                                + "en GET /api/v1/pagos/{id}. Los BFF web y movil llaman con el token del usuario."))
                .components(new Components().addSecuritySchemes("token", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
                        .description("Access token de banco-auth")))
                .addSecurityItem(new SecurityRequirement().addList("token"));
    }
}
