package com.bancoxyz.bff.movil.core;

import java.util.List;
import java.math.BigDecimal;
import com.bancoxyz.bff.movil.config.PropiedadesMovil;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

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
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Cliente HTTP real del core. Identidad y correlacion llegan como parametros, nunca desde el hilo. */
/**
 * Cliente HTTP de cuentas-service y pagos-service con el token del usuario (token relay) y el certificado de bff-movil
 * (mTLS). Circuito "core" con Resilience4j.
 */
@Component
public class ClienteCoreHttp implements CoreApi {

    private static final ParameterizedTypeReference<List<CuentaCore>> LISTA_DE_CUENTAS = new ParameterizedTypeReference<>() {
    };

    private final RestClient http;
    private final String cuentas;
    private final String pagos;
    private final CircuitBreakerFactory<?, ?> circuitos;

    public ClienteCoreHttp(RestClient.Builder builder, ObjectMapper json, PropiedadesCore core, PropiedadesMovil movil,
                           CircuitBreakerFactory<?, ?> circuitos) {
        this.circuitos = circuitos;
        this.cuentas = core.url();
        this.pagos = movil.pagosUrl();
        this.http = builder
                .defaultStatusHandler(HttpStatusCode::isError, (solicitud, respuesta) -> {
                    throw ErrorDelCore.desde(respuesta.getStatusCode().value(), respuesta.getBody().readAllBytes(), json);
                })
                .build();
    }

    @Override
    public List<CuentaCore> cuentasDeCliente(long clienteId, long usuarioId, String correlacion) {
        String token = tokenDelUsuario();
        return protegida(() -> http.get().uri(cuentas + "/clientes/{id}/cuentas", clienteId)
                .headers(h -> encabezados(h, token, correlacion))
                .retrieve()
                .body(LISTA_DE_CUENTAS));
    }

    @Override
    public PaginaMovimientosCore movimientos(long cuentaId, long usuarioId, int pagina, int tamano, String correlacion) {
        String token = tokenDelUsuario();
        return protegida(() -> http.get().uri(cuentas + "/cuentas/{id}/movimientos?pagina={p}&tamano={t}", cuentaId, pagina, tamano)
                .headers(h -> encabezados(h, token, correlacion))
                .retrieve()
                .body(PaginaMovimientosCore.class));
    }

    @Override
    public PagoCore transferir(long origen, long destino, BigDecimal monto, String clave, String correlacion) {
        String token = tokenDelUsuario();
        return protegida(() -> http.post().uri(pagos + "/pagos/transferencias")
                .headers(h -> {
                    encabezados(h, token, correlacion);
                    h.set("Idempotency-Key", clave);
                })
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("cuentaOrigen", origen, "cuentaDestino", destino, "monto", monto))
                .retrieve()
                .body(PagoCore.class));
    }

    @Override
    public PagoCore pago(String id, String correlacion) {
        String token = tokenDelUsuario();
        return protegida(() -> http.get().uri(pagos + "/pagos/{id}", id).headers(h -> encabezados(h, token, correlacion))
                .retrieve().body(PagoCore.class));
    }

    private <T> T protegida(Supplier<T> llamada) {
        return circuitos.create("core").run(llamada, causa -> {
            if (causa instanceof ErrorDelCore error) {
                throw error;
            }
            if (causa instanceof RestClientException error) {
                throw error;
            }
            throw new RestClientException("Circuito de los microservicios no disponible: " + causa, causa);
        });
    }

    /**
     * Token relay: el mismo access token que presento la app. Se lee en el hilo que atiende la solicitud (o en el de la
     * portada, que lo recibe del decorador) porque el circuito ejecuta la llamada en otro hilo.
     */
    private static String tokenDelUsuario() {
        return SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken jwt
                ? jwt.getToken().getTokenValue() : null;
    }

    private static void encabezados(HttpHeaders h, String token, String correlacion) {
        if (token != null) {
            h.setBearerAuth(token);
        }
        if (correlacion != null) {
            h.set(FiltroCorrelacion.ENCABEZADO, correlacion);
        }
    }
}
