package com.bancoxyz.clientes.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Propiedades de clientes-service bajo {@code banco.clientes}.
 *
 * @param zonaHoraria          zona del banco (marcas de tiempo y vencimiento de bloqueos)
 * @param sincronizarAlIniciar crear al arrancar los clientes del maestro que publico el batch
 * @param canalAutenticacion   usuario y clave Basic de banco-auth, el unico que verifica credenciales aqui
 * @param seguridad            costo de BCrypt para las claves de usuario
 * @param datosDemo            usuarios de demostracion que se crean al sincronizar
 */
@ConfigurationProperties(prefix = "banco.clientes")
public record PropiedadesClientes(String zonaHoraria, boolean sincronizarAlIniciar, CanalAutenticacion canalAutenticacion,
                                  Seguridad seguridad, DatosDemo datosDemo) {

    public record CanalAutenticacion(String usuario, String clave) {
    }

    public record Seguridad(int bcryptFuerza) {
    }

    public record DatosDemo(boolean habilitados, String claveClientes, String claveEjecutivo) {
    }
}
