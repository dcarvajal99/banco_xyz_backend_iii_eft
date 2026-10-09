package com.bancoxyz.bff.movil.error;

import java.net.URI;
import java.util.Locale;

import com.bancoxyz.bff.movil.config.FiltroCorrelacion;
import org.slf4j.MDC;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/** El ProblemDetail comun del banco; {@code detail} es el texto corto que la app muestra tal cual. */
public final class Problemas {

    private Problemas() {
    }

    public static ProblemDetail crear(HttpStatusCode estado, String codigo, String mensaje, String instancia) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(estado, mensaje);
        problema.setType(URI.create("urn:bancoxyz:problema:" + codigo.toLowerCase(Locale.ROOT).replace('_', '-')));
        problema.setTitle(codigo);
        problema.setInstance(URI.create(instancia == null || instancia.isBlank() ? "/" : instancia));
        problema.setProperty("codigo", codigo);
        problema.setProperty("correlacionId", MDC.get(FiltroCorrelacion.CLAVE_MDC));
        return problema;
    }
}
