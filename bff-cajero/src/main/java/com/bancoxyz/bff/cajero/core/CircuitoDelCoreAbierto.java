package com.bancoxyz.bff.cajero.core;

import org.springframework.web.client.RestClientException;

/**
 * El circuito hacia el core esta abierto: la solicitud NO salio del BFF.
 *
 * <p>Se distingue de una excepcion generica porque para el cajero la diferencia es de dinero: sin respuesta despues de
 * enviar un retiro, el resultado es incierto (504, reintentar con la misma clave); si el retiro nunca salio, no pudo
 * haber descuento (503, devolver la tarjeta).</p>
 */
public class CircuitoDelCoreAbierto extends RestClientException {

    public CircuitoDelCoreAbierto(Throwable causa) {
        super("Circuito del core abierto: la solicitud no se envio", causa);
    }
}
