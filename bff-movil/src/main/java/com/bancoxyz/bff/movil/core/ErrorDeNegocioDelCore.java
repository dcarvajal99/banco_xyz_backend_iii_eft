package com.bancoxyz.bff.movil.core;

import java.util.function.Predicate;

/**
 * Errores del core que NO deben abrir el circuito (propiedad {@code ignore-exception-predicate}).
 *
 * <p>Una respuesta de negocio (4xx: saldo insuficiente, credenciales invalidas, cuenta ajena) prueba que el core esta
 * sano: contarla como falla abriria el circuito por culpa de un usuario que se equivoco de clave. Tampoco cuenta
 * {@code CUENTA_OCUPADA} (503): indica contencion sobre UNA cuenta, y abrir el circuito por eso dejaria sin servicio a
 * todos los demas. Cualquier otro 5xx del core, en cambio, si es una falla y si cuenta.</p>
 */
public class ErrorDeNegocioDelCore implements Predicate<Throwable> {

    @Override
    public boolean test(Throwable error) {
        return error instanceof ErrorDelCore core && (core.getEstado() < 500 || "CUENTA_OCUPADA".equals(core.getCodigo()));
    }
}
