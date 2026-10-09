package com.bancoxyz.bff.web.panel;

import java.util.List;

import com.bancoxyz.bff.web.adaptador.CierreWeb;
import com.bancoxyz.bff.web.adaptador.CuentaWeb;
import com.bancoxyz.bff.web.adaptador.EstadoAnualWeb;
import com.bancoxyz.bff.web.adaptador.MovimientoWeb;
import com.bancoxyz.bff.web.adaptador.ResumenWeb;

/** El panel de la banca web: en una respuesta lo que el core entrega en 2N+2 llamadas. */
public record PanelClienteWeb(String titular, ResumenWeb resumen, List<CuentaWeb> cuentas,
                              List<MovimientoWeb> movimientosRecientes, List<EstadoAnualWeb> estadosAnuales,
                              CierreWeb cierre, List<String> advertencias) {
}
