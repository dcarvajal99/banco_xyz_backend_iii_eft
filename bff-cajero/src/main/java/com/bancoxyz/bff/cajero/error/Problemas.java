package com.bancoxyz.bff.cajero.error;

import java.net.URI;
import java.util.Locale;

import com.bancoxyz.bff.cajero.config.FiltroCorrelacion;
import org.slf4j.MDC;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/**
 * El mismo ProblemDetail que el resto del banco (type, title, status, detail, instance, codigo, correlacionId), mas lo
 * propio del cajero: el texto para la pantalla, la accion para el hardware y los intentos de PIN restantes.
 */
public final class Problemas {

    private Problemas() {
    }

    public static ProblemDetail desde(HttpStatusCode estado, RespuestaDeError error, String instancia) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(estado, error.mensajePantalla());
        problema.setType(URI.create("urn:bancoxyz:problema:" + error.codigo().toLowerCase(Locale.ROOT).replace('_', '-')));
        problema.setTitle(error.codigo());
        problema.setInstance(URI.create(instancia == null || instancia.isBlank() ? "/" : instancia));
        problema.setProperty("codigo", error.codigo());
        problema.setProperty("correlacionId", MDC.get(FiltroCorrelacion.CLAVE_MDC));
        problema.setProperty("mensajePantalla", error.mensajePantalla());
        problema.setProperty("accion", error.accion());
        if (error.intentosRestantes() != null) {
            problema.setProperty("intentosRestantes", error.intentosRestantes());
        }
        return problema;
    }
}
