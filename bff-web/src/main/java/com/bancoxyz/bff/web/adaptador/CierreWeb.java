package com.bancoxyz.bff.web.adaptador;

import java.time.LocalDateTime;

/** @param aviso texto para el usuario cuando el cierre se publico con calidad degradada; null si no hay nada que avisar */
public record CierreWeb(String conjunto, String calidad, long jobExecutionId, LocalDateTime finalizadoEn, String aviso) {
}
