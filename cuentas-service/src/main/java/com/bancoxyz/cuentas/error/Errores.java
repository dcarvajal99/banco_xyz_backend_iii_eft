package com.bancoxyz.cuentas.error;

import org.springframework.http.HttpStatus;

/** Catalogo de errores de cuentas-service. Un metodo por codigo; el codigo es el contrato con los BFF. */
public final class Errores {

    private Errores() {
    }

    public static ErrorDeNegocio solicitudInvalida(String detalle) {
        return new ErrorDeNegocio(HttpStatus.BAD_REQUEST, "SOLICITUD_INVALIDA", "Solicitud invalida", detalle);
    }


    public static ErrorDeNegocio montoInvalido() {
        return new ErrorDeNegocio(HttpStatus.BAD_REQUEST, "MONTO_INVALIDO", "Monto invalido",
                "El monto debe ser mayor que cero y sin decimales");
    }


    public static ErrorDeNegocio tarjetaInvalida() {
        return new ErrorDeNegocio(HttpStatus.UNAUTHORIZED, "TARJETA_INVALIDA", "Tarjeta invalida",
                "La tarjeta no es valida");
    }

    public static ErrorDeNegocio pinIncorrecto(int intentosRestantes) {
        return new ErrorDeNegocio(HttpStatus.UNAUTHORIZED, "PIN_INCORRECTO", "PIN incorrecto",
                "El PIN no corresponde a la tarjeta").con("intentosRestantes", intentosRestantes);
    }

    public static ErrorDeNegocio accesoDenegado() {
        return new ErrorDeNegocio(HttpStatus.FORBIDDEN, "ACCESO_DENEGADO", "Acceso denegado",
                "La credencial no tiene permiso sobre este recurso");
    }

    public static ErrorDeNegocio identidadDeCajeroRequerida(String encabezado) {
        return new ErrorDeNegocio(HttpStatus.BAD_REQUEST, "IDENTIDAD_REQUERIDA", "Identidad requerida",
                "Falta o es invalido el encabezado " + encabezado);
    }


    /** No distingue entre inexistente y ajena: responder 403 confirmaria que la cuenta existe. */
    public static ErrorDeNegocio cuentaNoEncontrada() {
        return new ErrorDeNegocio(HttpStatus.NOT_FOUND, "CUENTA_NO_ENCONTRADA", "Cuenta no encontrada",
                "La cuenta no existe o no pertenece al usuario");
    }


    public static ErrorDeNegocio clienteNoEncontrado() {
        return new ErrorDeNegocio(HttpStatus.NOT_FOUND, "CLIENTE_NO_ENCONTRADO", "Cliente no encontrado",
                "El cliente no existe o no corresponde al usuario");
    }

    public static ErrorDeNegocio idempotenciaConflicto() {
        return new ErrorDeNegocio(HttpStatus.CONFLICT, "IDEMPOTENCIA_CONFLICTO", "Clave de idempotencia en conflicto",
                "La clave de idempotencia ya se uso con otra solicitud");
    }

    public static ErrorDeNegocio saldoInsuficiente() {
        return new ErrorDeNegocio(HttpStatus.UNPROCESSABLE_ENTITY, "SALDO_INSUFICIENTE", "Saldo insuficiente",
                "El saldo disponible no alcanza para el monto solicitado");
    }

    public static ErrorDeNegocio limiteDiarioExcedido() {
        return new ErrorDeNegocio(HttpStatus.UNPROCESSABLE_ENTITY, "LIMITE_DIARIO_EXCEDIDO", "Limite diario excedido",
                "El retiro supera el limite diario de la tarjeta");
    }

    public static ErrorDeNegocio cuentaNoAdmiteRetiros() {
        return new ErrorDeNegocio(HttpStatus.UNPROCESSABLE_ENTITY, "CUENTA_NO_ADMITE_RETIROS",
                "La cuenta no admite retiros", "Solo las cuentas de ahorro admiten giros en cajero");
    }


    public static ErrorDeNegocio tarjetaBloqueada() {
        return new ErrorDeNegocio(HttpStatus.LOCKED, "TARJETA_BLOQUEADA", "Tarjeta bloqueada",
                "La tarjeta esta bloqueada");
    }

    public static ErrorDeNegocio sinCierrePublicado() {
        return new ErrorDeNegocio(HttpStatus.SERVICE_UNAVAILABLE, "SIN_CIERRE_PUBLICADO", "Sin cierre publicado",
                "Todavia no hay un cierre del batch publicado");
    }

    public static ErrorDeNegocio cuentaOcupada() {
        return new ErrorDeNegocio(HttpStatus.SERVICE_UNAVAILABLE, "CUENTA_OCUPADA", "Cuenta ocupada",
                "La cuenta esta siendo modificada por otra operacion. Reintente con la misma clave");
    }

    /** Cuenta bloqueada o cerrada: no admite movimientos. */
    public static ErrorDeNegocio cuentaNoOperativa() {
        return new ErrorDeNegocio(HttpStatus.UNPROCESSABLE_ENTITY, "CUENTA_NO_OPERATIVA", "Cuenta no operativa",
                "La cuenta esta bloqueada o cerrada");
    }

    public static ErrorDeNegocio cuentaCerrada() {
        return new ErrorDeNegocio(HttpStatus.CONFLICT, "CUENTA_CERRADA", "Cuenta cerrada",
                "La cuenta ya esta cerrada y no admite cambios");
    }

    public static ErrorDeNegocio cuentaConSaldo() {
        return new ErrorDeNegocio(HttpStatus.UNPROCESSABLE_ENTITY, "CUENTA_CON_SALDO", "Cuenta con saldo",
                "Para cerrar la cuenta su saldo disponible debe ser cero");
    }

    /** clientes-service no respondio y el cliente no tiene cuentas aqui con las que conocer al titular. */
    public static ErrorDeNegocio clientesNoDisponible() {
        return new ErrorDeNegocio(HttpStatus.SERVICE_UNAVAILABLE, "CLIENTES_NO_DISPONIBLE", "Clientes no disponible",
                "No se pudo verificar al cliente. Intente mas tarde");
    }
}
