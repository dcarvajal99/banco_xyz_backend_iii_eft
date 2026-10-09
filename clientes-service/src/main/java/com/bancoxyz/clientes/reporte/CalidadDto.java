package com.bancoxyz.clientes.reporte;

import java.util.List;

import com.bancoxyz.clientes.cierre.CierrePublicado;

/** Forma del contrato {@code calidad.json}. */
public record CalidadDto(List<CierrePublicado> cierres, List<RechazadoDto> rechazados) {
}
