package com.bancoxyz.auth.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param emisor           claim {@code iss}: la URL con que los servicios conocen a banco-auth
 * @param audiencia        claim {@code aud}: los servicios del banco solo aceptan tokens emitidos para ellos
 * @param duracionToken    vida del access token; tambien el tiempo de gracia de una clave retirada del JWK Set
 * @param duracionRefresco vida del refresh token (rota en cada uso)
 * @param bffWeb           BFF web: cliente confidencial, authorization_code + PKCE + refresh token
 * @param bancaMovil       app movil: cliente PUBLICO (no puede guardar un secreto), authorization_code + PKCE
 * @param bffCajero        BFF del cajero: client_credentials con cajero.operar
 * @param pagos            pagos-service: client_credentials con cuentas.leer (prevalidacion)
 * @param cuentas          cuentas-service: client_credentials con clientes.leer (titular de una apertura)
 * @param operacion        operacion del banco: client_credentials con claves.administrar
 */
@ConfigurationProperties(prefix = "banco.auth")
public record PropiedadesAuth(String emisor, String audiencia, Duration duracionToken, Duration duracionRefresco,
                              Cliente bffWeb, Cliente bancaMovil, Cliente bffCajero, Cliente pagos, Cliente cuentas,
                              Cliente operacion) {

    /** Un cliente OAuth registrado: id, secreto (por variable de entorno) y, si aplica, sus redirect URIs. */
    public record Cliente(String id, String secreto, List<String> redirectUris) {
    }
}
