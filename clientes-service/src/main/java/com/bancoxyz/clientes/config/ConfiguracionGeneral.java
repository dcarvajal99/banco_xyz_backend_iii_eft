package com.bancoxyz.clientes.config;

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

    /** Un unico reloj con la zona del banco: las pruebas lo reemplazan por uno ajustable (bloqueos que vencen). */
    @Bean
    public Clock reloj(PropiedadesClientes propiedades) {
        return Clock.system(ZoneId.of(propiedades.zonaHoraria()));
    }

    @Bean
    public OpenAPI contratoOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Banco XYZ - clientes-service")
                        .version("1.0.0")
                        .description("Gestion de clientes: informacion personal, perfiles y credenciales. Los BFF llaman "
                                + "con el access token del usuario emitido por banco-auth (scopes clientes.*); banco-auth "
                                + "verifica credenciales en /api/v1/autenticacion con su clave de servicio y su certificado."))
                .components(new Components()
                        .addSecuritySchemes("tokenDeUsuario", new SecurityScheme().type(SecurityScheme.Type.HTTP)
                                .scheme("bearer").bearerFormat("JWT").description("Access token de banco-auth"))
                        .addSecuritySchemes("servicioDeAutenticacion", new SecurityScheme().type(SecurityScheme.Type.HTTP)
                                .scheme("basic").description("banco-auth (mas su certificado de cliente)")))
                .addSecurityItem(new SecurityRequirement().addList("tokenDeUsuario"));
    }
}
