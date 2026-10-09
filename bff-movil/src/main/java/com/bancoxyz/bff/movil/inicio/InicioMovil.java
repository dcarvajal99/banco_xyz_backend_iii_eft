package com.bancoxyz.bff.movil.inicio;

import java.math.BigDecimal;
import java.util.List;

import com.bancoxyz.bff.movil.adaptador.CuentaMovil;
import com.bancoxyz.bff.movil.adaptador.MovimientoMovil;

/** La portada de la app en una sola respuesta: lo que el cliente ve al abrirla. */
public record InicioMovil(String nombre, BigDecimal disponible, BigDecimal deuda, List<CuentaMovil> cuentas,
                          List<MovimientoMovil> ultimos) {
}
