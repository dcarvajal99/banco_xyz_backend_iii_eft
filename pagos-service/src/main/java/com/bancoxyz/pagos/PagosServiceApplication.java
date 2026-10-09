package com.bancoxyz.pagos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Procesamiento de Pagos del Banco XYZ: transferencias, depositos y pagos de servicios.
 * Registra la solicitud, aplica las reglas de fraude y la publica en Kafka; cuentas-service mueve los saldos.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class PagosServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PagosServiceApplication.class, args);
    }
}
