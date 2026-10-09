package com.bancoxyz.bff.web.error;

import java.net.URI;
import java.util.Locale;

import com.bancoxyz.bff.web.config.FiltroCorrelacion;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/** ProblemDetail completo del canal web: el navegador tiene espacio para mostrar el detalle. */
public final class ProblemasWeb {

    private ProblemasWeb() {
    }

    public static ProblemDetail crear(HttpStatusCode estado, String codigo, String titulo, String detalle, String instancia) {
        ProblemDetail problema = ProblemDetail.forStatus(estado);
        problema.setType(URI.create("urn:bancoxyz:web:" + codigo.toLowerCase(Locale.ROOT).replace('_', '-')));
        problema.setTitle(titulo);
        problema.setDetail(detalle == null ? titulo : detalle);
        problema.setInstance(URI.create(instancia == null || instancia.isBlank() ? "/" : instancia));
        problema.setProperty("codigo", codigo);
        problema.setProperty("correlacionId", FiltroCorrelacion.actual());
        return problema;
    }
}
