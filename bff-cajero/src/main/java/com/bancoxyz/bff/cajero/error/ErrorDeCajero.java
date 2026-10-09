package com.bancoxyz.bff.cajero.error;

import org.springframework.http.HttpStatus;

public class ErrorDeCajero extends RuntimeException {

    private final HttpStatus estado;
    private final transient RespuestaDeError respuesta;

    public ErrorDeCajero(HttpStatus estado, String codigo, String mensajePantalla, String accion) {
        this(estado, codigo, mensajePantalla, accion, null);
    }

    public ErrorDeCajero(HttpStatus estado, String codigo, String mensajePantalla, String accion,
                         Integer intentosRestantes) {
        super(codigo);
        this.estado = estado;
        this.respuesta = new RespuestaDeError(codigo, mensajePantalla, accion, intentosRestantes);
    }

    public HttpStatus getEstado() {
        return estado;
    }

    public RespuestaDeError getRespuesta() {
        return respuesta;
    }
}
