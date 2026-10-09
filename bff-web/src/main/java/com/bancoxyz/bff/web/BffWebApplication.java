package com.bancoxyz.bff.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * BFF del canal web (navegador): login con banco-auth, sesion HttpOnly + CSRF, datos completos y vistas agregadas
 * (panel del cliente y consola del ejecutivo) sobre cuentas-service, clientes-service y pagos-service.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class BffWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(BffWebApplication.class, args);
    }
}
