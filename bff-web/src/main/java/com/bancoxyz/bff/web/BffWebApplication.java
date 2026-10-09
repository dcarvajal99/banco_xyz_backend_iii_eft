package com.bancoxyz.bff.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Backend for Frontend del navegador: la banca en linea del cliente y la consola del ejecutivo.
 *
 * <p>Pantallas grandes y complejas: datos completos (tasas, intereses, anomalias del batch), paginacion con
 * metadatos, series listas para graficar y un panel que junta en una respuesta lo que el core entrega en varias.</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class BffWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(BffWebApplication.class, args);
    }
}
