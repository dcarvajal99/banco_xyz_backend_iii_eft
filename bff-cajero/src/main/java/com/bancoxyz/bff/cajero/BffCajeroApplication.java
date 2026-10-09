package com.bancoxyz.bff.cajero;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Backend for Frontend de los cajeros automaticos.
 *
 * <p>Su cliente es un aparato fisico desatendido que dispensa dinero. Por eso autentica dos cosas en
 * cada operacion —el terminal, con una firma HMAC por solicitud, y el tarjetahabiente, con tarjeta y
 * PIN— y responde lo minimo que necesita la pantalla y el hardware: un texto y una accion.</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class BffCajeroApplication {

    public static void main(String[] args) {
        SpringApplication.run(BffCajeroApplication.class, args);
    }
}
