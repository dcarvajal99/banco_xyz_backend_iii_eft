package com.bancoxyz.bff.cajero.core;

import java.math.BigDecimal;
import java.util.Map;
import java.util.function.Supplier;

import com.bancoxyz.bff.cajero.config.FiltroCorrelacion;
import com.bancoxyz.bff.cajero.config.PropiedadesCore;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.support.BasicAuthenticationInterceptor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Cliente HTTP del core para el canal cajero.
 *
 * <p>La identidad (tarjeta y terminal) y la correlacion llegan como parametros explicitos y no se leen
 * de ningun contexto ligado al hilo: el metodo hace exactamente lo que dice su firma.</p>
 *
 * <p>No llama a {@code requestFactory(...)}: los timeouts vienen de {@code spring.http.client.*}. Si este
 * codigo reemplazara la fabrica de conexiones, el simulador de las pruebas quedaria anulado.</p>
 */
@Component
public class ClienteCoreCajero {

    public static final String ENCABEZADO_TARJETA = "X-Tarjeta-Id";
    public static final String ENCABEZADO_TERMINAL = "X-Terminal-Id";
    public static final String ENCABEZADO_IDEMPOTENCIA = "Idempotency-Key";
    public static final String ENCABEZADO_REPETIDA = "Idempotency-Replayed";

    /** @param repetida el core reconocio la clave y devolvio el resultado original sin descontar de nuevo */
    public record RespuestaRetiro(OperacionCore operacion, boolean repetida) {
    }

    private final RestClient http;
    private final CircuitBreakerFactory<?, ?> circuitos;

    public ClienteCoreCajero(RestClient.Builder builder, ObjectMapper json, PropiedadesCore core,
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

    public TarjetaAutenticadaCore autenticarTarjeta(String numeroTarjeta, String pin, String correlacion) {
        return protegida(() -> http.post().uri("/autenticacion/tarjetas")
                .headers(h -> correlacion(h, correlacion))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("numeroTarjeta", numeroTarjeta, "pin", pin))
                .retrieve()
                .body(TarjetaAutenticadaCore.class));
    }

    public CuentaCore cuenta(long cuentaId, long tarjetaId, String terminal, String correlacion) {
        return protegida(() -> http.get().uri("/cuentas/{id}", cuentaId)
                .headers(h -> {
                    identidad(h, tarjetaId, terminal);
                    correlacion(h, correlacion);
                })
                .retrieve()
                .body(CuentaCore.class));
    }

    public RespuestaRetiro retirar(long cuentaId, long tarjetaId, String terminal, String claveIdempotencia,
                                   BigDecimal monto, String correlacion) {
        ResponseEntity<OperacionCore> respuesta = protegida(() -> http.post().uri("/cuentas/{id}/retiros", cuentaId)
                .headers(h -> {
                    identidad(h, tarjetaId, terminal);
                    h.set(ENCABEZADO_IDEMPOTENCIA, claveIdempotencia);
                    correlacion(h, correlacion);
                })
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("monto", monto))
                .retrieve()
                .toEntity(OperacionCore.class));
        return new RespuestaRetiro(respuesta.getBody(),
                "true".equalsIgnoreCase(respuesta.getHeaders().getFirst(ENCABEZADO_REPETIDA)));
    }

    /** Fallback conservador: si el core falla, el BFF no consulta saldo ni autoriza retiros. */
    private <T> T protegida(Supplier<T> llamada) {
        return circuitos.create("core").run(llamada, causa -> {
            if (causa instanceof ErrorDelCore error) {
                throw error;
            }
            if (causa instanceof RestClientException error) {
                throw error;
            }
            if (causa instanceof CallNotPermittedException) {
                throw new CircuitoDelCoreAbierto(causa);
            }
            if (causa instanceof IllegalStateException sinInstancias && sinInstancias.getMessage() != null
                    && sinInstancias.getMessage().startsWith("No instances available")) {
                // Spring Cloud LoadBalancer no encontro el core en Eureka: la solicitud no salio.
                throw new CoreNoRegistrado(causa);
            }
            // Cualquier otra causa (por ejemplo, el limite de tiempo del circuito) ocurre con la solicitud ya enviada.
            throw new RestClientException("Llamada al core interrumpida", causa);
        });
    }

    private static void identidad(HttpHeaders h, long tarjetaId, String terminal) {
        h.set(ENCABEZADO_TARJETA, String.valueOf(tarjetaId));
        h.set(ENCABEZADO_TERMINAL, terminal);
    }

    private static void correlacion(HttpHeaders h, String correlacion) {
        if (correlacion != null) {
            h.set(FiltroCorrelacion.ENCABEZADO, correlacion);
        }
    }
}
