package com.bancoxyz.pagos.pago;

import java.util.Optional;

import com.bancoxyz.pagos.config.PropiedadesPagos;
import org.springframework.stereotype.Component;

/**
 * Reglas de fraude, sin Spring ni Kafka alrededor (se prueban solas). Se evaluan en orden y gana la primera que
 * aplica. Los valores llegan del Config Server: el area de riesgo los cambia sin desplegar codigo.
 */
@Component
public class ReglasDeFraude {

    public static final String MONTO_SOBRE_LIMITE = "MONTO_SOBRE_LIMITE";
    public static final String CUENTA_EN_OBSERVACION = "CUENTA_EN_OBSERVACION";

    private final PropiedadesPagos.Fraude reglas;

    public ReglasDeFraude(PropiedadesPagos propiedades) {
        this.reglas = propiedades.fraude();
    }

    /** Regla que el pago infringe, o vacio si se puede procesar. */
    public Optional<String> evaluar(Pago pago) {
        if (pago.getMonto().compareTo(reglas.montoMaximo()) > 0) {
            return Optional.of(MONTO_SOBRE_LIMITE);
        }
        if (enObservacion(pago.getCuentaOrigen()) || enObservacion(pago.getCuentaDestino())) {
            return Optional.of(CUENTA_EN_OBSERVACION);
        }
        return Optional.empty();
    }

    private boolean enObservacion(Long cuenta) {
        return cuenta != null && reglas.cuentasEnObservacion() != null && reglas.cuentasEnObservacion().contains(cuenta);
    }
}
