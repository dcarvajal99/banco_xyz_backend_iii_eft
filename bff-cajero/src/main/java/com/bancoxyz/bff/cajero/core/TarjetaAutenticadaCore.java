package com.bancoxyz.bff.cajero.core;

/** DTO propio del contrato tarjeta-autenticada.json. Sin libreria compartida con el core, a proposito. */
public record TarjetaAutenticadaCore(long tarjetaId, long cuentaId, String ultimos4, String titular) {
}
