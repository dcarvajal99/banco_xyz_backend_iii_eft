package com.bancoxyz.bff.cajero.core;

import org.springframework.web.client.RestClientException;

/**
 * Eureka no tiene ninguna instancia del core: la solicitud NO salio del BFF porque no habia a quien enviarla.
 *
 * <p>Como con el circuito abierto, no pudo haber descuento: el cajero responde 503 y devuelve la tarjeta. Sin esta
 * distincion, el error del balanceador ({@code IllegalStateException}) se habria informado como resultado incierto
 * (504), ordenando reintentar un retiro que nunca se envio.</p>
 */
public class CoreNoRegistrado extends RestClientException {

    public CoreNoRegistrado(Throwable causa) {
        super("El core no tiene instancias registradas en Eureka: la solicitud no se envio", causa);
    }
}
