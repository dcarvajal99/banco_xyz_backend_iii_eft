package com.bancoxyz.bff.cajero.adaptador;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import com.bancoxyz.bff.cajero.core.ErrorDelCore;
import com.bancoxyz.bff.cajero.error.ErrorDeCajero;
import com.bancoxyz.bff.cajero.error.TraductorDeErroresDelCore;
import com.bancoxyz.bff.cajero.seguridad.RegistroDeRevocaciones;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

class AdaptadorYTraductorTest {

    private final TraductorDeErroresDelCore traductor = new TraductorDeErroresDelCore();

    @Test
    @DisplayName("Formatos de pantalla: pesos chilenos, cuenta enmascarada y titular abreviado")
    void formatos() {
        assertThat(AdaptadorCajero.pesos(new BigDecimal("12060.00"))).isEqualTo("$12.060");
        assertThat(AdaptadorCajero.pesos(new BigDecimal("200000"))).isEqualTo("$200.000");
        assertThat(AdaptadorCajero.pesos(new BigDecimal("25"))).isEqualTo("$25");
        assertThat(AdaptadorCajero.cuentaEnmascarada(101)).isEqualTo("****0101");
        assertThat(AdaptadorCajero.cuentaEnmascarada(123456)).isEqualTo("****3456");
        assertThat(AdaptadorCajero.titularAbreviado("Diana Prince")).isEqualTo("Diana P.");
        assertThat(AdaptadorCajero.titularAbreviado("Madonna")).isEqualTo("Madonna");
        assertThat(AdaptadorCajero.titularAbreviado(" ")).isEmpty();
        assertThat(AdaptadorCajero.titularAbreviado(null)).isEmpty();
    }

    @Test
    @DisplayName("Sesion: singular del ultimo intento y cualquier otra falla devuelve la tarjeta")
    void traduccionEnSesion() {
        assertThat(traductor.enSesion(new ErrorDelCore(401, "PIN_INCORRECTO", 1)).getRespuesta().mensajePantalla())
                .isEqualTo("PIN incorrecto. Le queda 1 intento.");
        assertThat(traductor.enSesion(new ErrorDelCore(500, null, null)).getRespuesta().codigo()).isEqualTo("SERVICIO_NO_DISPONIBLE");
        assertThat(traductor.enSesion(new ErrorDelCore(401, "NO_AUTENTICADO", null)).getRespuesta().accion()).isEqualTo("DEVOLVER_TARJETA");
        assertThat(traductor.enSesion(new ResourceAccessException("caido")).getEstado().value()).isEqualTo(503);
        assertThat(traductor.enConsulta(new ErrorDelCore(404, "CUENTA_NO_ENCONTRADA", null)).getEstado().value()).isEqualTo(503);
    }

    @Test
    @DisplayName("Retiro: 5xx desconocido es incierto, 4xx no listado no hubo descuento, sin conexion devuelve tarjeta")
    void traduccionEnRetiro() {
        ErrorDeCajero incierto = traductor.enRetiro(new ErrorDelCore(502, null, null));
        assertThat(incierto.getEstado().value()).isEqualTo(504);
        assertThat(incierto.getRespuesta().accion()).isEqualTo("REINTENTAR_MISMA_CLAVE");
        assertThat(traductor.enRetiro(new ErrorDelCore(404, "CUENTA_NO_ENCONTRADA", null)).getEstado().value()).isEqualTo(503);
        assertThat(traductor.enRetiro(new ErrorDelCore(423, "TARJETA_BLOQUEADA", null)).getRespuesta().accion()).isEqualTo("RETENER_TARJETA");
        assertThat(traductor.enRetiro(new ErrorDelCore(400, "MONTO_INVALIDO", null)).getEstado().value()).isEqualTo(400);
        assertThat(traductor.enRetiro(new ErrorDelCore(422, "CUENTA_NO_ADMITE_RETIROS", null)).getEstado().value()).isEqualTo(422);
        assertThat(traductor.enRetiro(new ResourceAccessException("x", new ConnectException("refused"))).getEstado().value())
                .isEqualTo(503);
        assertThat(traductor.enRetiro(new ResourceAccessException("x", new IOException("reset"))).getEstado().value())
                .isEqualTo(504);
    }

    @Test
    @DisplayName("Las revocaciones vencidas se olvidan")
    void revocacionesVencidas() {
        Clock reloj = Clock.fixed(Instant.parse("2026-09-12T20:00:00Z"), ZoneId.of("UTC"));
        RegistroDeRevocaciones registro = new RegistroDeRevocaciones(reloj);
        registro.revocar("vigente", Instant.parse("2026-09-12T20:01:00Z"));
        registro.revocar("vencido", Instant.parse("2026-09-12T19:59:00Z"));
        registro.revocar(null, null);
        registro.revocar("sin-vencimiento", null);
        assertThat(registro.estaRevocado("vigente")).isTrue();
        assertThat(registro.estaRevocado("vencido")).isFalse();
        assertThat(registro.estaRevocado("sin-vencimiento")).isTrue();
        assertThat(registro.estaRevocado(null)).isFalse();
    }
}
