package com.bancoxyz.clientes.aviso;

import java.time.LocalDateTime;

public record AvisoDto(long id, String tipo, String titulo, String mensaje, boolean seguridad, LocalDateTime creadaEn) {

    static AvisoDto de(Aviso a) {
        return new AvisoDto(a.getId(), a.getTipo(), a.getTitulo(), a.getMensaje(), a.isSeguridad(), a.getCreadaEn());
    }
}
