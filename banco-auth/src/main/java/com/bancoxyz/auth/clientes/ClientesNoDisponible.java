package com.bancoxyz.auth.clientes;

/** El core no respondio o el circuito esta abierto: sin el core no se puede verificar una clave, no se emite token. */
public class ClientesNoDisponible extends RuntimeException {

    public ClientesNoDisponible(Throwable causa) {
        super("El core no esta disponible para verificar la clave", causa);
    }
}
