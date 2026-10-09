package com.bancoxyz.cuentas.pago;

/** Mensaje que no es un evento legible. No se reintenta: va directo al topico DLT. */
public class EventoInvalido extends RuntimeException {

    public EventoInvalido(String detalle, Throwable causa) {
        super(detalle, causa);
    }
}
