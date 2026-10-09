package com.bancoxyz.bff.web.servicios;

/** Microservicios que consume el BFF web; el nombre es tambien el de su circuito de Resilience4j. */
public enum Servicio {
    CUENTAS("cuentas"), CLIENTES("clientes"), PAGOS("pagos");

    private final String circuito;

    Servicio(String circuito) {
        this.circuito = circuito;
    }

    public String circuito() {
        return circuito;
    }
}
