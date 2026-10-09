package com.bancoxyz.bff.cajero.sesion;

public record RespuestaSesion(String tokenSesion, long expiraEn, String titular, String accion) {
}
