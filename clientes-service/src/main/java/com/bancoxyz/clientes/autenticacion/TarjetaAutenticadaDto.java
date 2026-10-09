package com.bancoxyz.clientes.autenticacion;

/** Forma del contrato {@code tarjeta-autenticada.json}. */
public record TarjetaAutenticadaDto(long tarjetaId, long cuentaId, String ultimos4, String titular) {
}
