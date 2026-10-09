package com.bancoxyz.clientes.cliente;

import java.time.LocalDateTime;

/**
 * Ficha completa de un cliente (respuesta de GET /clientes/{id}, PUT /perfil y POST /clientes).
 *
 * @param usuario nombre de usuario con el que ingresa a la banca; vacio si aun no tiene credenciales
 * @param version version del perfil: el BFF la muestra y un 409 PERFIL_MODIFICADO indica que hay que releer
 */
public record ClienteDto(long clienteId, String nombre, Integer edad, String email, String telefono, String direccion,
                         String origen, String usuario, LocalDateTime actualizadoEn, long version) {

    static ClienteDto de(Cliente c, String usuario) {
        return new ClienteDto(c.getId(), c.getNombre(), c.getEdad(), c.getEmail(), c.getTelefono(), c.getDireccion(),
                c.getOrigen(), usuario, c.getActualizadoEn(), c.getVersion());
    }
}
