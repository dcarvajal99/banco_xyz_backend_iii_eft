package com.bancoxyz.bff.web.panel;

import java.util.List;

import com.bancoxyz.bff.web.adaptador.CalidadWeb;
import com.bancoxyz.bff.web.adaptador.ClienteWeb;
import com.bancoxyz.bff.web.adaptador.FilaResumenWeb;

/** La consola del ejecutivo: clientes, calidad de los cierres y el pulso de las transacciones. */
public record PanelEjecutivoWeb(List<ClienteWeb> clientes, CalidadWeb calidad, List<FilaResumenWeb> resumenDiarioUltimos,
                                List<String> alertas, List<String> advertencias) {
}
