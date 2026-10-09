package com.bancoxyz.bff.cajero.core;

import java.io.IOException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Respuesta de error del core, con el codigo del catalogo si venia en el ProblemDetail. */
public class ErrorDelCore extends RuntimeException {

    private final int estado;
    private final String codigo;
    private final Integer intentosRestantes;

    public ErrorDelCore(int estado, String codigo, Integer intentosRestantes) {
        super("Core respondio " + estado + " " + codigo);
        this.estado = estado;
        this.codigo = codigo;
        this.intentosRestantes = intentosRestantes;
    }

    static ErrorDelCore desde(int estado, byte[] cuerpo, ObjectMapper json) {
        String codigo = null;
        Integer intentos = null;
        try {
            JsonNode nodo = cuerpo.length == 0 ? null : json.readTree(cuerpo);
            if (nodo != null) {
                codigo = nodo.path("codigo").isTextual() ? nodo.get("codigo").asText() : null;
                intentos = nodo.path("intentosRestantes").isNumber() ? nodo.get("intentosRestantes").asInt() : null;
            }
        } catch (IOException cuerpoQueNoEsJson) {
            // Un proxy intermedio puede responder HTML: se conserva solo el estado.
        }
        return new ErrorDelCore(estado, codigo, intentos);
    }

    public int getEstado() {
        return estado;
    }

    public String getCodigo() {
        return codigo;
    }

    public Integer getIntentosRestantes() {
        return intentosRestantes;
    }
}
