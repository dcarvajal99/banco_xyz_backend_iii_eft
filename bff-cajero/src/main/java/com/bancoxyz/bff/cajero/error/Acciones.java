package com.bancoxyz.bff.cajero.error;

/** Lo que el software del terminal debe hacer despues de mostrar el mensaje. */
public final class Acciones {

    public static final String MOSTRAR_MENU = "MOSTRAR_MENU";
    public static final String SOLICITAR_PIN = "SOLICITAR_PIN";
    public static final String DISPENSAR = "DISPENSAR";
    public static final String DEVOLVER_TARJETA = "DEVOLVER_TARJETA";
    public static final String RETENER_TARJETA = "RETENER_TARJETA";
    public static final String REINTENTAR_MISMA_CLAVE = "REINTENTAR_MISMA_CLAVE";
    public static final String NINGUNA = "NINGUNA";

    private Acciones() {
    }
}
