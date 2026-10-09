package com.bancoxyz.clientes.error;

import org.springframework.http.HttpStatus;

/** Catalogo de errores de clientes-service. Un metodo por codigo; el codigo es el contrato con los BFF y banco-auth. */
public final class Errores {

    private Errores() {
    }

    public static ErrorDeNegocio solicitudInvalida(String detalle) {
        return new ErrorDeNegocio(HttpStatus.BAD_REQUEST, "SOLICITUD_INVALIDA", "Solicitud invalida", detalle);
    }

    public static ErrorDeNegocio credencialesInvalidas() {
        return new ErrorDeNegocio(HttpStatus.UNAUTHORIZED, "CREDENCIALES_INVALIDAS", "Credenciales invalidas",
                "Usuario o clave incorrectos");
    }

    public static ErrorDeNegocio accesoDenegado() {
        return new ErrorDeNegocio(HttpStatus.FORBIDDEN, "ACCESO_DENEGADO", "Acceso denegado",
                "El usuario no tiene permiso sobre este recurso");
    }

    public static ErrorDeNegocio rolNoPermitido() {
        return new ErrorDeNegocio(HttpStatus.FORBIDDEN, "ROL_NO_PERMITIDO", "Rol no permitido",
                "Este tipo de usuario no puede ingresar por esta via");
    }

    /** Inexistente o inactivo responden igual: no se revela que usuarios existen. */
    public static ErrorDeNegocio usuarioNoEncontrado() {
        return new ErrorDeNegocio(HttpStatus.NOT_FOUND, "USUARIO_NO_ENCONTRADO", "Usuario no encontrado",
                "El usuario no existe o no esta activo");
    }

    /** No distingue entre inexistente y ajeno: responder 403 confirmaria que el cliente existe. */
    public static ErrorDeNegocio clienteNoEncontrado() {
        return new ErrorDeNegocio(HttpStatus.NOT_FOUND, "CLIENTE_NO_ENCONTRADO", "Cliente no encontrado",
                "El cliente no existe o no corresponde al usuario");
    }

    public static ErrorDeNegocio clienteYaExiste() {
        return new ErrorDeNegocio(HttpStatus.CONFLICT, "CLIENTE_YA_EXISTE", "Cliente ya existe",
                "Ya hay un cliente registrado con ese nombre");
    }

    public static ErrorDeNegocio usuarioYaExiste() {
        return new ErrorDeNegocio(HttpStatus.CONFLICT, "USUARIO_YA_EXISTE", "Usuario ya existe",
                "El nombre de usuario ya esta en uso");
    }

    /** Dos ediciones del mismo perfil a la vez: gana la primera y la segunda debe releer. */
    public static ErrorDeNegocio perfilModificado() {
        return new ErrorDeNegocio(HttpStatus.CONFLICT, "PERFIL_MODIFICADO", "Perfil modificado",
                "El perfil cambio mientras se editaba. Vuelva a consultarlo e intente de nuevo");
    }

    public static ErrorDeNegocio usuarioBloqueado() {
        return new ErrorDeNegocio(HttpStatus.LOCKED, "USUARIO_BLOQUEADO", "Usuario bloqueado",
                "Demasiados intentos fallidos. Intente mas tarde");
    }
}
