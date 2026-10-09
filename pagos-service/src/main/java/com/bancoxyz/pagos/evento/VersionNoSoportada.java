package com.bancoxyz.pagos.evento;

/**
 * Evento de una version del contrato que este servicio todavia no entiende. No se reintenta ni se adivina: va a la DLT
 * para reprocesarlo cuando el consumidor se actualice.
 */
public class VersionNoSoportada extends EventoInvalido {

    public VersionNoSoportada(int version, String origen) {
        super("Version " + version + " del evento no soportada en " + origen + ": este servicio entiende hasta la "
                + EventoDeMovimiento.VERSION_ACTUAL, null);
    }
}
