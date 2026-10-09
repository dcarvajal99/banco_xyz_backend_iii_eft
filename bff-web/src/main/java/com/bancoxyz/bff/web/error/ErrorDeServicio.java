package com.bancoxyz.bff.web.error;

import com.fasterxml.jackson.databind.JsonNode;

/** Un microservicio respondio con error: se devuelve al navegador con el mismo estado y el mismo ProblemDetail. */
public class ErrorDeServicio extends RuntimeException {

    private final int estado;
    private final transient JsonNode problema;

    public ErrorDeServicio(int estado, JsonNode problema) {
        super("Respuesta " + estado + " de un microservicio");
        this.estado = estado;
        this.problema = problema;
    }

    public int getEstado() {
        return estado;
    }

    public JsonNode getProblema() {
        return problema;
    }
}
