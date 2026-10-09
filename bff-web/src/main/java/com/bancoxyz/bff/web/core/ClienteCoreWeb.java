package com.bancoxyz.bff.web.core;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.bancoxyz.bff.web.config.FiltroCorrelacion;
import com.bancoxyz.bff.web.config.PropiedadesCore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.support.BasicAuthenticationInterceptor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Cliente HTTP del core para el canal web.
 *
 * <p>Cada metodo recibe el id del usuario y la correlacion como parametros. Este cliente se llama desde los hilos
 * del agregador del panel, donde no hay contexto de seguridad ni MDC confiables: nada se lee del hilo.</p>
 */
@Component
public class ClienteCoreWeb {

    private static final String ENCABEZADO_USUARIO = "X-Usuario-Id";

    private final RestClient http;
    private final CircuitBreakerFactory<?, ?> circuitos;

    public ClienteCoreWeb(RestClient.Builder builder, ObjectMapper json, PropiedadesCore core,
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

    public UsuarioAutenticadoCore autenticar(String usuario, String password, String correlacion) {
        return protegida(() -> http.post().uri("/autenticacion/usuarios")
                .headers(h -> correlacion(h, correlacion))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("usuario", usuario, "password", password))
                .retrieve()
                .body(UsuarioAutenticadoCore.class));
    }

    public List<CuentaCore> cuentasDeCliente(long clienteId, long usuarioId, String correlacion) {
        return protegida(() -> http.get().uri("/clientes/{id}/cuentas", clienteId)
                .headers(h -> identidad(h, usuarioId, correlacion))
                .retrieve()
                .body(new ParameterizedTypeReference<List<CuentaCore>>() {
                }));
    }

    public CuentaCore cuenta(long cuentaId, long usuarioId, String correlacion) {
        return protegida(() -> http.get().uri("/cuentas/{id}", cuentaId)
                .headers(h -> identidad(h, usuarioId, correlacion))
                .retrieve()
                .body(CuentaCore.class));
    }

    public PaginaMovimientosCore movimientos(long cuentaId, long usuarioId, int pagina, int tamano, String correlacion) {
        return protegida(() -> http.get().uri(u -> u.path("/cuentas/{id}/movimientos").queryParam("pagina", pagina)
                        .queryParam("tamano", tamano).build(cuentaId))
                .headers(h -> identidad(h, usuarioId, correlacion))
                .retrieve()
                .body(PaginaMovimientosCore.class));
    }

    public List<EstadoAnualCore> estadosAnuales(long cuentaId, long usuarioId, String correlacion) {
        return protegida(() -> http.get().uri("/cuentas/{id}/estados-anuales", cuentaId)
                .headers(h -> identidad(h, usuarioId, correlacion))
                .retrieve()
                .body(new ParameterizedTypeReference<List<EstadoAnualCore>>() {
                }));
    }

    public List<ClienteDelBancoCore> clientes(long usuarioId, String correlacion) {
        return protegida(() -> http.get().uri("/clientes")
                .headers(h -> identidad(h, usuarioId, correlacion))
                .retrieve()
                .body(new ParameterizedTypeReference<List<ClienteDelBancoCore>>() {
                }));
    }

    public List<ResumenDiarioCore> resumenDiario(long usuarioId, LocalDate desde, LocalDate hasta, String correlacion) {
        return protegida(() -> http.get().uri(u -> {
                    u.path("/reportes/resumen-diario");
                    if (desde != null) {
                        u.queryParam("desde", desde);
                    }
                    if (hasta != null) {
                        u.queryParam("hasta", hasta);
                    }
                    return u.build();
                })
                .headers(h -> identidad(h, usuarioId, correlacion))
                .retrieve()
                .body(new ParameterizedTypeReference<List<ResumenDiarioCore>>() {
                }));
    }

    public CalidadCore calidad(long usuarioId, String correlacion) {
        return protegida(() -> http.get().uri("/reportes/calidad")
                .headers(h -> identidad(h, usuarioId, correlacion))
                .retrieve()
                .body(CalidadCore.class));
    }

    public List<CierreCore> cierresVigentes(String correlacion) {
        return protegida(() -> http.get().uri("/cierres/vigentes")
                .headers(h -> correlacion(h, correlacion))
                .retrieve()
                .body(new ParameterizedTypeReference<List<CierreCore>>() {
                }));
    }

    /** Circuit breaker comun: los errores de negocio conservan su contrato; una caida activa el fallback 503. */
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

    private static void identidad(HttpHeaders h, long usuarioId, String correlacion) {
        h.set(ENCABEZADO_USUARIO, String.valueOf(usuarioId));
        correlacion(h, correlacion);
    }

    private static void correlacion(HttpHeaders h, String correlacion) {
        if (correlacion != null) {
            h.set(FiltroCorrelacion.ENCABEZADO, correlacion);
        }
    }
}
