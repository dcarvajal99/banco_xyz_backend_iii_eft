package com.bancoxyz.pagos.cuentas;

import java.util.Objects;

import com.bancoxyz.pagos.config.PropiedadesPagos;
import com.bancoxyz.pagos.error.ErrorDeNegocio;
import com.bancoxyz.pagos.error.Errores;
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
 * Prevalida la cuenta de origen en cuentas-service: Bulkhead + CircuitBreaker + Retry ("cuentas").
 *
 * <p>Comportamiento alternativo: si cuentas-service no responde, el pago no se rechaza; queda con validacion DIFERIDA y
 * cuentas-service vuelve a validar todo al aplicarlo. Con el circuito abierto el fallback es inmediato: el cliente no
 * espera timeouts. Un 404 si es una respuesta: la cuenta no existe o no es del cliente.</p>
 */
@Component
public class ClienteDeCuentas {

    private static final Logger log = LoggerFactory.getLogger(ClienteDeCuentas.class);

    record CuentaRemota(long cuentaId, Long clienteId, String estado) {
    }

    private final RestClient http;

    public ClienteDeCuentas(RestClient.Builder builder, PropiedadesPagos propiedades,
                            OAuth2AuthorizedClientManager gestorDeTokens) {
        this.http = builder.baseUrl(propiedades.cuentasUrl())
                .requestInterceptor(TokenDeServicio.interceptor(gestorDeTokens))
                .defaultStatusHandler(HttpStatusCode::is4xxClientError, (solicitud, respuesta) -> {
                    throw Errores.cuentaOrigenNoEncontrada();
                })
                .build();
    }

    @Bulkhead(name = "cuentas")
    @CircuitBreaker(name = "cuentas")
    @Retry(name = "cuentas", fallbackMethod = "validacionDiferida")
    public Validacion validarCuentaDeOrigen(long clienteId, long cuentaOrigen) {
        CuentaRemota cuenta = http.get().uri("/cuentas/{id}", cuentaOrigen).retrieve().body(CuentaRemota.class);
        if (cuenta == null || !Objects.equals(cuenta.clienteId(), clienteId)) {
            throw Errores.cuentaOrigenNoEncontrada();
        }
        return Validacion.PREVIA;
    }

    /** Cualquier falla tecnica (sin conexion, 5xx, circuito abierto, bulkhead lleno, sin token, sin instancias). */
    Validacion validacionDiferida(long clienteId, long cuentaOrigen, Exception falla) {
        if (falla instanceof ErrorDeNegocio negocio) {
            throw negocio;
        }
        log.warn("cuentas-service no disponible ({}): la cuenta {} se valida al aplicar el pago (DIFERIDA)",
                falla.getClass().getSimpleName(), cuentaOrigen);
        return Validacion.DIFERIDA;
    }
}
