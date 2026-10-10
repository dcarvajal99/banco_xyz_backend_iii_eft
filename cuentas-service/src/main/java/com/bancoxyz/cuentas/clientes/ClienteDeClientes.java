package com.bancoxyz.cuentas.clientes;

import com.bancoxyz.cuentas.config.PropiedadesCuentas;
import com.bancoxyz.cuentas.cuenta.Cuenta;
import com.bancoxyz.cuentas.cuenta.RepositorioCuentas;
import com.bancoxyz.cuentas.error.Errores;
import com.bancoxyz.cuentas.config.FiltroCorrelacion;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Verifica en clientes-service al titular de una cuenta nueva: Bulkhead + CircuitBreaker + Retry ("clientes").
 *
 * <p>Comportamiento alternativo: si clientes-service no responde, se usa el titular que ya figura en otra cuenta del
 * mismo cliente (dato propio de este servicio). Si el cliente no tiene cuentas aqui, no hay como conocerlo y la
 * apertura se rechaza con 503 CLIENTES_NO_DISPONIBLE: no se abre una cuenta a nombre de nadie.</p>
 */
@Component
public class ClienteDeClientes {

    private static final Logger log = LoggerFactory.getLogger(ClienteDeClientes.class);

    /** Nombre del titular y si se confirmo en linea con clientes-service. */
    public record Titular(String nombre, boolean verificadoEnLinea) {
    }

    record ClienteRemoto(long clienteId, String nombre) {
    }

    private final RestClient http;
    private final RepositorioCuentas cuentas;

    public ClienteDeClientes(RestClient.Builder builder, PropiedadesCuentas propiedades,
                             OAuth2AuthorizedClientManager gestorDeTokens, RepositorioCuentas cuentas) {
        this.cuentas = cuentas;
        this.http = builder.baseUrl(propiedades.clientes().url())
                .requestInterceptor(TokenDeServicio.interceptor(gestorDeTokens))
                // El id de correlacion de esta solicitud viaja con la llamada: la operacion se sigue en los logs de ambos.
                .requestInterceptor((solicitud, cuerpo, ejecucion) -> {
                    if (FiltroCorrelacion.actual() != null) {
                        solicitud.getHeaders().set(FiltroCorrelacion.ENCABEZADO, FiltroCorrelacion.actual());
                    }
                    return ejecucion.execute(solicitud, cuerpo);
                })
                .defaultStatusHandler(HttpStatusCode::is4xxClientError, (solicitud, respuesta) -> {
                    // 404: el cliente no existe. No es una falla de clientes-service (el circuito lo ignora).
                    throw Errores.clienteNoEncontrado();
                })
                .build();
    }

    @Bulkhead(name = "clientes")
    @CircuitBreaker(name = "clientes")
    @Retry(name = "clientes", fallbackMethod = "titularConocido")
    public Titular titular(long clienteId) {
        ClienteRemoto cliente = http.get().uri("/clientes/{id}", clienteId).retrieve().body(ClienteRemoto.class);
        return new Titular(cliente.nombre(), true);
    }

    /** Fallback de cualquier falla tecnica (sin conexion, 5xx, circuito abierto, bulkhead lleno, sin token). */
    Titular titularConocido(long clienteId, Exception falla) {
        if (falla instanceof com.bancoxyz.cuentas.error.ErrorDeNegocio negocio) {
            throw negocio;
        }
        log.warn("clientes-service no disponible ({}): se busca el titular del cliente {} en sus cuentas",
                falla.getClass().getSimpleName(), clienteId);
        return cuentas.findFirstByClienteIdOrderByIdAsc(clienteId)
                .map(Cuenta::getTitular)
                .map(nombre -> new Titular(nombre, false))
                .orElseThrow(Errores::clientesNoDisponible);
    }
}
