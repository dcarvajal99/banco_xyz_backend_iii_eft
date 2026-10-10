package com.bancoxyz.bff.web.servicios;

import java.io.IOException;
import java.util.Map;
import java.util.function.Supplier;

import com.bancoxyz.bff.web.config.PropiedadesWeb;
import com.bancoxyz.bff.web.error.ErrorDeServicio;
import com.bancoxyz.bff.web.error.ServicioNoDisponible;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

/**
 * Llamadas a los microservicios con el access token del usuario (token relay), el certificado de bff-web (mTLS) y un
 * circuito de Resilience4j por servicio. Un error de negocio (4xx) del servicio llega al navegador tal cual; una falla
 * tecnica o un circuito abierto se convierten en {@link ServicioNoDisponible} (el panel decide si puede seguir sin ese
 * dato).
 */
@Component
public class ClienteServicios {

    /** Id del registro OAuth (application.properties): el login con banco-auth. */
    static final String REGISTRO = "banco-auth";

    private final RestClient http;
    private final Map<Servicio, String> bases;
    private final CircuitBreakerFactory<?, ?> circuitos;

    public ClienteServicios(RestClient.Builder builder, PropiedadesWeb propiedades, OAuth2AuthorizedClientManager gestor,
                            CircuitBreakerFactory<?, ?> circuitos, ObjectMapper json) {
        this.circuitos = circuitos;
        this.bases = Map.of(Servicio.CUENTAS, propiedades.cuentasUrl(), Servicio.CLIENTES, propiedades.clientesUrl(),
                Servicio.PAGOS, propiedades.pagosUrl());
        OAuth2ClientHttpRequestInterceptor relevo = new OAuth2ClientHttpRequestInterceptor(gestor);
        relevo.setClientRegistrationIdResolver(solicitud -> REGISTRO);
        this.http = builder
                .requestInterceptor(relevo)
                .defaultStatusHandler(estado -> estado.is4xxClientError() || estado.value() == 503, (solicitud, respuesta) -> {
                    byte[] cuerpo = respuesta.getBody().readAllBytes();
                    JsonNode problema = leer(json, cuerpo);
                    // Un 503 con codigo es una respuesta controlada: el servicio esta arriba pero le falta una
                    // dependencia (p. ej. CLIENTES_NO_DISPONIBLE). Llega tal cual y no abre el circuito de ese servicio.
                    if (respuesta.getStatusCode().is4xxClientError() || problema.hasNonNull("codigo")) {
                        throw new ErrorDeServicio(respuesta.getStatusCode().value(), problema);
                    }
                    throw HttpServerErrorException.create(respuesta.getStatusCode(), respuesta.getStatusText(),
                            respuesta.getHeaders(), cuerpo, null);
                })
                .build();
    }

    public JsonNode get(Servicio servicio, String ruta, Object... variables) {
        return protegida(servicio, () -> http.get().uri(bases.get(servicio) + ruta, variables).retrieve().body(JsonNode.class));
    }

    /** POST/PUT/PATCH reenviando el cuerpo y la Idempotency-Key; devuelve estado, Location y cuerpo del servicio. */
    public ResponseEntity<JsonNode> enviar(HttpMethod metodo, Servicio servicio, String ruta, Object cuerpo,
                                           String claveIdempotencia, Object... variables) {
        return protegida(servicio, () -> http.method(metodo).uri(bases.get(servicio) + ruta, variables)
                .headers(h -> {
                    if (claveIdempotencia != null) {
                        h.set("Idempotency-Key", claveIdempotencia);
                    }
                })
                .contentType(MediaType.APPLICATION_JSON)
                .body(cuerpo)
                .retrieve()
                .toEntity(JsonNode.class));
    }

    /**
     * Circuito por servicio. Sin TimeLimiter, sin pool de hilos y con bulkhead de semaforo (application.properties): la llamada corre en
     * el hilo de la solicitud, donde estan la sesion y el token del usuario.
     */
    private <T> T protegida(Servicio servicio, Supplier<T> llamada) {
        return circuitos.create(servicio.circuito()).run(llamada, causa -> {
            // El circuito puede entregar la falla envuelta: se busca el error de negocio en la cadena de causas.
            for (Throwable t = causa; t != null; t = t.getCause()) {
                if (t instanceof ErrorDeServicio negocio) {
                    throw negocio;
                }
            }
            throw new ServicioNoDisponible(servicio.name().toLowerCase() + "-service", causa);
        });
    }

    private static JsonNode leer(ObjectMapper json, byte[] cuerpo) {
        try {
            return cuerpo.length == 0 ? json.createObjectNode() : json.readTree(cuerpo);
        } catch (IOException noEsJson) {
            return json.createObjectNode().put("codigo", "RESPUESTA_INVALIDA");
        }
    }
}
