package com.bancoxyz.bff.web.core;

import java.time.LocalDateTime;

public record CierreCore(String conjunto, long jobExecutionId, String jobNombre, String estado, String calidad,
                         LocalDateTime finalizadoEn) {
}
