package com.bancoxyz.bff.movil.error;

import org.springframework.http.HttpStatus;

public class ErrorMovil extends RuntimeException {

    private final HttpStatus estado;
    private final String codigo;

    public ErrorMovil(HttpStatus estado, String codigo, String mensaje) {
        super(mensaje);
        this.estado = estado;
        this.codigo = codigo;
    }

    public HttpStatus getEstado() {
        return estado;
    }

    public String getCodigo() {
        return codigo;
    }
}
