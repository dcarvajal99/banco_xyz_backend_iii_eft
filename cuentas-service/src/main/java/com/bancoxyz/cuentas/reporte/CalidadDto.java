package com.bancoxyz.cuentas.reporte;

import java.util.List;

import com.bancoxyz.cuentas.cierre.CierrePublicado;

/** Forma del contrato {@code calidad.json}. */
public record CalidadDto(List<CierrePublicado> cierres, List<RechazadoDto> rechazados) {
}
