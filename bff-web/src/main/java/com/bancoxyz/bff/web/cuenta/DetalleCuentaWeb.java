package com.bancoxyz.bff.web.cuenta;

import java.util.List;

import com.bancoxyz.bff.web.adaptador.CuentaWeb;
import com.bancoxyz.bff.web.adaptador.EstadoAnualWeb;
import com.bancoxyz.bff.web.adaptador.PaginaWeb;

public record DetalleCuentaWeb(CuentaWeb cuenta, List<EstadoAnualWeb> estadosAnuales, PaginaWeb movimientos) {
}
