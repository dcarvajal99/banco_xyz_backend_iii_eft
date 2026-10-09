package com.bancoxyz.bff.web.core;

public record UsuarioAutenticadoCore(long usuarioId, Long clienteId, String usuario, String nombre, String rol) {
}
