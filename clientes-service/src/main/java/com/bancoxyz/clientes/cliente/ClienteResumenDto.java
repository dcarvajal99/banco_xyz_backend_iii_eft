package com.bancoxyz.clientes.cliente;

/** Fila del listado de clientes de la consola del ejecutivo: lo justo para buscar y abrir la ficha. */
public record ClienteResumenDto(long clienteId, String nombre, String usuario, String email, String origen) {
}
