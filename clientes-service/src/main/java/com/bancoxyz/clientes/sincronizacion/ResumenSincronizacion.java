package com.bancoxyz.clientes.sincronizacion;

/**
 * Resultado de copiar el maestro de clientes de la migracion.
 *
 * @param conMigracion {@code false} si el batch aun no publico el maestro
 */
public record ResumenSincronizacion(boolean conMigracion, int clientesEnMigracion, int clientesNuevos, int usuariosNuevos) {

    static ResumenSincronizacion sinMigracion() {
        return new ResumenSincronizacion(false, 0, 0, 0);
    }
}
