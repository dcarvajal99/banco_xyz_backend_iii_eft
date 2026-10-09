package com.bancoxyz.bff.web.core;

/** DTO propio del contrato clientes.json. */
public record ClienteDelBancoCore(long clienteId, String nombre, String usuario, long cantidadCuentas) {
}
