package com.bancoxyz.clientes.operacion;

/** @param repetida true si la clave de idempotencia ya se habia usado con la misma solicitud */
public record ResultadoRetiro(Operacion operacion, boolean repetida) {
}
