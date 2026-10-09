package com.bancoxyz.bff.movil;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Backend for Frontend de la app movil.
 *
 * <p>Pantalla chica, datos moviles y red inestable: respuestas con lo esencial, sin nulos, comprimidas y
 * con ETag para no volver a descargar lo que no cambio. Autenticacion con token corto y refresh rotativo,
 * porque un telefono se pierde.</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class BffMovilApplication {

    public static void main(String[] args) {
        SpringApplication.run(BffMovilApplication.class, args);
    }
}
