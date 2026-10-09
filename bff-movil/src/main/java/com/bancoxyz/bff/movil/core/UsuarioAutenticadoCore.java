package com.bancoxyz.bff.movil.core;

/** DTO propio del contrato usuario-autenticado.json. */
public record UsuarioAutenticadoCore(long usuarioId, Long clienteId, String usuario, String nombre, String rol) {
}
