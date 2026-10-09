package com.bancoxyz.bff.cajero.seguridad;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Sesiones cerradas antes de vencer (el cliente saco la tarjeta). Un JWT no se puede "borrar": se recuerda su
 * {@code jti} hasta que vence por si solo, y no un segundo mas.
 */
@Component
public class RegistroDeRevocaciones {

    private final Map<String, Instant> revocados = new ConcurrentHashMap<>();
    private final Clock reloj;

    public RegistroDeRevocaciones(Clock reloj) {
        this.reloj = reloj;
    }

    public void revocar(String jti, Instant vence) {
        if (jti != null) {
            revocados.put(jti, vence == null ? reloj.instant().plusSeconds(3600) : vence);
        }
    }

    public boolean estaRevocado(String jti) {
        Instant ahora = reloj.instant();
        revocados.values().removeIf(vence -> vence.isBefore(ahora));
        return jti != null && revocados.containsKey(jti);
    }
}
