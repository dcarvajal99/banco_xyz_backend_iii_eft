package com.bancoxyz.bff.web.error;

/** Un microservicio no respondio o su circuito esta abierto (fallback de Resilience4j). */
public class ServicioNoDisponible extends RuntimeException {

    private final String servicio;

    public ServicioNoDisponible(String servicio, Throwable causa) {
        super(servicio + " no disponible", causa);
        this.servicio = servicio;
    }

    public String getServicio() {
        return servicio;
    }
}
