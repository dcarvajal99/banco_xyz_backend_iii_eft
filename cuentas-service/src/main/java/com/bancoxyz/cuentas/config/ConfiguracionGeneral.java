package com.bancoxyz.cuentas.config;

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
    public Clock reloj(PropiedadesCuentas propiedades) {
        return Clock.system(ZoneId.of(propiedades.zonaHoraria()));
    }

    @Bean
    public OpenAPI contratoOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Banco XYZ - cuentas-service")
                        .version("1.0.0")
                        .description("Gestion de cuentas: apertura, cierre y mantenimiento; saldos, movimientos, estados "
                                + "anuales, tarjetas y retiros en cajero; participante de la saga de pagos. Los BFF web y "
                                + "movil llaman con el access token del usuario (token relay); el BFF del cajero y "
                                + "pagos-service con su propio token de servicio. Todos presentan su certificado (mTLS)."))
                .components(new Components().addSecuritySchemes("token", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
                        .description("Access token de banco-auth")))
                .addSecurityItem(new SecurityRequirement().addList("token"));
    }
}
