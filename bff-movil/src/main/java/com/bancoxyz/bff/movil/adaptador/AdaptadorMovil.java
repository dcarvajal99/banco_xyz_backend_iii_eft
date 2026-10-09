package com.bancoxyz.bff.movil.adaptador;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

import com.bancoxyz.bff.movil.core.CuentaCore;
import com.bancoxyz.bff.movil.core.MovimientoCore;
import org.springframework.stereotype.Component;

/**
 * Adapter: de la respuesta completa del core a lo esencial para el telefono.
 *
 * <p>De la cuenta quedan 3 de 12 campos; de un movimiento, 3 de 9. Tasas, intereses, anomalias y observaciones
 * del batch no viajan por la red movil: la app no los muestra.</p>
 */
@Component
public class AdaptadorMovil {

    static final int LARGO_DESCRIPCION = 24;

    public CuentaMovil cuenta(CuentaCore cuenta) {
        return new CuentaMovil(cuenta.cuentaId(), cuenta.tipo(), cuenta.saldoDisponible());
    }

    public MovimientoMovil movimiento(MovimientoCore movimiento) {
        return new MovimientoMovil(movimiento.fecha(), recortar(movimiento.descripcion()), movimiento.monto());
    }

    /** Lo que el cliente puede usar: la suma de sus cuentas de ahorro. */
    public BigDecimal disponible(List<CuentaCore> cuentas) {
        return sumar(cuentas.stream().filter(c -> "ahorro".equals(c.tipo())).toList());
    }

    /** Lo que el cliente debe: prestamos e hipotecas. Sumarlo al disponible no tendria sentido. */
    public BigDecimal deuda(List<CuentaCore> cuentas) {
        return sumar(cuentas.stream().filter(c -> "prestamo".equals(c.tipo()) || "hipoteca".equals(c.tipo())).toList());
    }

    public List<MovimientoMovil> ultimos(List<MovimientoCore> movimientos, int cantidad) {
        return movimientos.stream()
                .sorted(Comparator.comparing(MovimientoCore::fecha).reversed().thenComparing(MovimientoCore::id, Comparator.reverseOrder()))
                .limit(cantidad)
                .map(this::movimiento)
                .toList();
    }

    public static String primerNombre(String nombre) {
        if (nombre == null || nombre.isBlank()) {
            return "";
        }
        return nombre.trim().split("\\s+")[0];
    }

    static String recortar(String texto) {
        if (texto == null) {
            return null;
        }
        return texto.length() <= LARGO_DESCRIPCION ? texto : texto.substring(0, LARGO_DESCRIPCION - 1) + "…";
    }

    private static BigDecimal sumar(List<CuentaCore> cuentas) {
        return cuentas.stream().map(CuentaCore::saldoDisponible).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
