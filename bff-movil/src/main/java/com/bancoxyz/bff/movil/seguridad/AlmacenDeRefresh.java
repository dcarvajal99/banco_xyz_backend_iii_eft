package com.bancoxyz.bff.movil.seguridad;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

import com.bancoxyz.bff.movil.config.PropiedadesMovil;
import com.bancoxyz.bff.movil.error.ErrorMovil;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Refresh tokens rotativos con deteccion de reutilizacion.
 *
 * <h2>Rotacion</h2>
 * <p>Cada renovacion consume el refresh token y entrega uno nuevo de la misma familia. Un refresh token sirve
 * una sola vez.</p>
 *
 * <h2>Reutilizacion = robo</h2>
 * <p>Si llega un refresh token YA USADO, alguien tiene una copia: o el ladron lo uso primero y ahora llega el
 * dueno, o al reves. No hay forma de saber cual es cual, asi que se revoca la familia completa y ambos deben
 * volver a iniciar sesion. Tambien quedan invalidos los access tokens vigentes de esa familia.</p>
 *
 * <p>Solo se guarda el SHA-256 del token: una copia de la memoria no permite renovar sesiones. Estado en
 * memoria de un proceso: con varias instancias iria a un almacen compartido (limitacion documentada).</p>
 */
@Component
public class AlmacenDeRefresh {

    private static final SecureRandom ALEATORIO = new SecureRandom();

    record Registro(long usuarioId, long clienteId, String nombre, String dispositivo, String familia, Instant vence,
                    boolean usado) {
    }

    public record Renovacion(long usuarioId, long clienteId, String nombre, String dispositivo, String familia) {
    }

    private final Map<String, Registro> porHuella = new HashMap<>();
    private final Set<String> familiasRevocadas = new HashSet<>();
    private final Clock reloj;
    private final Duration vida;

    public AlmacenDeRefresh(Clock reloj, PropiedadesMovil propiedades) {
        this.reloj = reloj;
        this.vida = Duration.ofDays(propiedades.refreshDias());
    }

    public synchronized String emitir(long usuarioId, long clienteId, String nombre, String dispositivo, String familia) {
        byte[] bytes = new byte[32];
        ALEATORIO.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        porHuella.put(huella(token), new Registro(usuarioId, clienteId, nombre, dispositivo, familia,
                reloj.instant().plus(vida), false));
        return token;
    }

    public synchronized Renovacion consumir(String token, String dispositivo) {
        Registro registro = token == null ? null : porHuella.get(huella(token));
        if (registro == null || registro.vence().isBefore(reloj.instant()) || familiasRevocadas.contains(registro.familia())) {
            throw new ErrorMovil(HttpStatus.UNAUTHORIZED, "REFRESH_INVALIDO", "Inicie sesión nuevamente.");
        }
        if (registro.usado()) {
            familiasRevocadas.add(registro.familia());
            throw new ErrorMovil(HttpStatus.UNAUTHORIZED, "REFRESH_REUTILIZADO",
                    "Detectamos un uso indebido de su sesión. Inicie sesión nuevamente.");
        }
        if (!registro.dispositivo().equals(dispositivo)) {
            throw new ErrorMovil(HttpStatus.UNAUTHORIZED, "DISPOSITIVO_NO_COINCIDE", "Sesión de otro dispositivo.");
        }
        porHuella.put(huella(token), new Registro(registro.usuarioId(), registro.clienteId(), registro.nombre(),
                registro.dispositivo(), registro.familia(), registro.vence(), true));
        return new Renovacion(registro.usuarioId(), registro.clienteId(), registro.nombre(), registro.dispositivo(),
                registro.familia());
    }

    public synchronized void revocarFamilia(String familia) {
        if (familia != null) {
            familiasRevocadas.add(familia);
        }
    }

    public synchronized boolean familiaRevocada(String familia) {
        return familia != null && familiasRevocadas.contains(familia);
    }

    private static String huella(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
