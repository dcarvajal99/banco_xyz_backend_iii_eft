package com.bancoxyz.bff.web.error;

import org.springframework.http.HttpStatus;

public class ErrorWeb extends RuntimeException {

    private final HttpStatus estado;
    private final String codigo;
    private final String titulo;

    public ErrorWeb(HttpStatus estado, String codigo, String titulo, String detalle) {
        super(detalle);
        this.estado = estado;
        this.codigo = codigo;
        this.titulo = titulo;
    }

    public HttpStatus getEstado() {
        return estado;
    }

    public String getCodigo() {
        return codigo;
    }

    public String getTitulo() {
        return titulo;
    }
}
