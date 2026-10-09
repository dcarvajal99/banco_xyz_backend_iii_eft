package com.bancoxyz.cuentas.cierre;

/** Resultado de abrir las cuentas del cierre publicado. */
public record ResumenSincronizacion(boolean conCierre, long jobExecutionId, String calidad, int cuentasEnCierre,
                                    int cuentasNuevas, int tarjetasNuevas) {

    static ResumenSincronizacion sinCierre() {
        return new ResumenSincronizacion(false, 0, null, 0, 0, 0);
    }
}
