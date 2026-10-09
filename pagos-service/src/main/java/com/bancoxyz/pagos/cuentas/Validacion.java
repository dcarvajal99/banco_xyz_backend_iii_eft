package com.bancoxyz.pagos.cuentas;

/**
 * PREVIA: cuentas-service confirmo la cuenta de origen al recibir la solicitud. DIFERIDA: no respondio y la valida al
 * aplicar el pago (fallback). NO_APLICA: un deposito no tiene cuenta de origen que validar.
 */
public enum Validacion {
    PREVIA, DIFERIDA, NO_APLICA
}
