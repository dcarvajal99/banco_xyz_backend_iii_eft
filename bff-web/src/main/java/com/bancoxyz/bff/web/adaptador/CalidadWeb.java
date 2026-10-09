package com.bancoxyz.bff.web.adaptador;

import java.util.List;

import com.bancoxyz.bff.web.core.RechazadoCore;

public record CalidadWeb(List<CierreWeb> cierres, List<RechazadoCore> rechazados, long totalRechazados, String aviso) {
}
