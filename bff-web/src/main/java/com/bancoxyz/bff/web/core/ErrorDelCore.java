package com.bancoxyz.bff.web.core;

import java.io.IOException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class ErrorDelCore extends RuntimeException {

    private final int estado;
    private final String codigo;

    public ErrorDelCore(int estado, String codigo) {
        super("Core respondio " + estado + " " + codigo);
        this.estado = estado;
        this.codigo = codigo;
    }

    static ErrorDelCore desde(int estado, byte[] cuerpo, ObjectMapper json) {
        String codigo = null;
        try {
            JsonNode nodo = cuerpo.length == 0 ? null : json.readTree(cuerpo);
            if (nodo != null && nodo.path("codigo").isTextual()) {
                codigo = nodo.get("codigo").asText();
            }
        } catch (IOException cuerpoQueNoEsJson) {
            // se conserva solo el estado
        }
        return new ErrorDelCore(estado, codigo);
    }

    public int getEstado() {
        return estado;
    }

    public String getCodigo() {
        return codigo;
    }
}
