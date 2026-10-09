package com.bancoxyz.bff.movil.movimientos;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.bancoxyz.bff.movil.error.ErrorMovil;
import org.springframework.http.HttpStatus;

/**
 * Cursor opaco para "cargar mas". Hoy codifica la pagina y el tamano del core; la app no lo interpreta, asi que
 * mañana puede pasar a codificar el id del ultimo movimiento visto sin cambiar la app.
 */
public record Cursor(int pagina, int limite) {

    public String codificar() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((pagina + ":" + limite).getBytes(StandardCharsets.UTF_8));
    }

    public static Cursor decodificar(String valor) {
        try {
            String[] partes = new String(Base64.getUrlDecoder().decode(valor), StandardCharsets.UTF_8).split(":");
            Cursor cursor = new Cursor(Integer.parseInt(partes[0]), Integer.parseInt(partes[1]));
            if (partes.length != 2 || cursor.pagina() < 0 || cursor.limite() < 1 || cursor.limite() > ControladorDeMovimientos.LIMITE_MAXIMO) {
                throw new IllegalArgumentException("cursor fuera de rango");
            }
            return cursor;
        } catch (RuntimeException e) {
            throw new ErrorMovil(HttpStatus.BAD_REQUEST, "SOLICITUD_INVALIDA", "Cursor inválido.");
        }
    }
}
