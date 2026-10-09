package com.bancoxyz.pagos.error;

import org.springframework.http.HttpStatus;

/** Catalogo de errores de pagos-service. Un metodo por codigo; el codigo es el contrato con los BFF. */
public final class Errores {

    private Errores() {
    }

    public static ErrorDeNegocio solicitudInvalida(String detalle) {
        return new ErrorDeNegocio(HttpStatus.BAD_REQUEST, "SOLICITUD_INVALIDA", "Solicitud invalida", detalle);
    }

    public static ErrorDeNegocio accesoDenegado() {
        return new ErrorDeNegocio(HttpStatus.FORBIDDEN, "ACCESO_DENEGADO", "Acceso denegado",
                "La credencial no tiene permiso sobre este recurso");
    }

    public static ErrorDeNegocio pagoNoEncontrado() {
        return new ErrorDeNegocio(HttpStatus.NOT_FOUND, "PAGO_NO_ENCONTRADO", "Pago no encontrado",
                "No existe un pago suyo con ese id");
    }

    /** cuentas-service respondio 404: la cuenta no existe o no es del cliente (no se distingue a proposito). */
    public static ErrorDeNegocio cuentaOrigenNoEncontrada() {
        return new ErrorDeNegocio(HttpStatus.NOT_FOUND, "CUENTA_ORIGEN_NO_ENCONTRADA", "Cuenta de origen no encontrada",
                "La cuenta de origen no existe o no es suya");
    }

    public static ErrorDeNegocio mismaCuenta() {
        return new ErrorDeNegocio(HttpStatus.UNPROCESSABLE_ENTITY, "MISMA_CUENTA", "Misma cuenta",
                "La cuenta de origen y la de destino son la misma");
    }

    public static ErrorDeNegocio idempotenciaConflicto() {
        return new ErrorDeNegocio(HttpStatus.CONFLICT, "IDEMPOTENCIA_CONFLICTO", "Clave de idempotencia en conflicto",
                "Esa clave de idempotencia ya se uso con otra solicitud");
    }
}
