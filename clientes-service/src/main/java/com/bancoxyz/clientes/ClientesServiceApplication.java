package com.bancoxyz.clientes;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Gestion de Clientes del Banco XYZ: informacion personal, perfiles y credenciales.
 *
 * <p>Es dueno del esquema {@code clientes}. Los BFF web y movil lo llaman con el token del usuario (token relay);
 * banco-auth verifica aqui las claves de usuario. Publica alertas de seguridad (usuario bloqueado) en Kafka.</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ClientesServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClientesServiceApplication.class, args);
    }
}
