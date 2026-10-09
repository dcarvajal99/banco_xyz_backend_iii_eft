package com.bancoxyz.bff.movil.core;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.bancoxyz.bff.movil.config.FiltroCorrelacion;
import com.bancoxyz.bff.movil.config.PropiedadesCore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.support.BasicAuthenticationInterceptor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Cliente HTTP real del core. Identidad y correlacion llegan como parametros, nunca desde el hilo. */
@Component
public class ClienteCoreHttp implements CoreApi {

    private static final ParameterizedTypeReference<List<CuentaCore>> LISTA_DE_CUENTAS = new ParameterizedTypeReference<>() {
    };

    private final RestClient http;
    private final CircuitBreakerFactory<?, ?> circuitos;

    public ClienteCoreHttp(RestClient.Builder builder, ObjectMapper json, PropiedadesCore core,
                           CircuitBreakerFactory<?, ?> circuitos) {
        this.circuitos = circuitos;
        this.http = builder
                .baseUrl(core.url())
                .requestInterceptor(new BasicAuthenticationInterceptor(core.usuario(), core.clave()))
                .defaultStatusHandler(HttpStatusCode::isError, (solicitud, respuesta) -> {
                    throw ErrorDelCore.desde(respuesta.getStatusCode().value(), respuesta.getBody().readAllBytes(), json);
                })
                .build();
    }

    @Override
    public UsuarioAutenticadoCore autenticar(String usuario, String password, String correlacion) {
        return protegida(() -> http.post().uri("/autenticacion/usuarios")
                .headers(h -> correlacion(h, correlacion))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("usuario", usuario, "password", password))
                .retrieve()
                .body(UsuarioAutenticadoCore.class));
    }

    @Override
    public List<CuentaCore> cuentasDeCliente(long clienteId, long usuarioId, String correlacion) {
        return protegida(() -> http.get().uri("/clientes/{id}/cuentas", clienteId)
                .headers(h -> {
                    h.set("X-Usuario-Id", String.valueOf(usuarioId));
                    correlacion(h, correlacion);
                })
                .retrieve()
                .body(LISTA_DE_CUENTAS));
    }

    @Override
    public PaginaMovimientosCore movimientos(long cuentaId, long usuarioId, int pagina, int tamano, String correlacion) {
        return protegida(() -> http.get().uri(u -> u.path("/cuentas/{id}/movimientos").queryParam("pagina", pagina)
                        .queryParam("tamano", tamano).build(cuentaId))
                .headers(h -> {
                    h.set("X-Usuario-Id", String.valueOf(usuarioId));
                    correlacion(h, correlacion);
                })
                .retrieve()
                .body(PaginaMovimientosCore.class));
    }

    /** Abre el circuito ante fallos tecnicos y devuelve el fallback ligero del canal movil. */
    private <T> T protegida(Supplier<T> llamada) {
        return circuitos.create("core").run(llamada, causa -> {
            if (causa instanceof ErrorDelCore error) {
                throw error;
            }
            if (causa instanceof RestClientException error) {
                throw error;
            }
            throw new RestClientException("Circuito del core no disponible", causa);
        });
    }

    private static void correlacion(HttpHeaders h, String correlacion) {
        if (correlacion != null) {
            h.set(FiltroCorrelacion.ENCABEZADO, correlacion);
        }
    }
}
