package com.bancoxyz.bff.cajero;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.x509;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import com.bancoxyz.bff.cajero.seguridad.FirmaHmac;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.client.AutoConfigureMockRestServiceServer;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Tolerancia a fallos del canal cajero con la politica del circuito que trae el BFF: la misma que entrega el Config
 * Server (banco-xyz-cloud/configuracion/bff-cajero.properties), comprobado por banco-xyz-cloud/scripts/verificar_coherencia.sh.
 * Se prueba la politica de produccion, no una copia pensada para la prueba.
 */
@ActiveProfiles("prueba")
@SpringBootTest(properties = "spring.jackson.deserialization.fail-on-missing-creator-properties=true")
@AutoConfigureMockMvc
@AutoConfigureMockRestServiceServer
class CircuitoDelCoreTest {

    static final String CORE = "https://localhost:8080/api/v1";
    static final String ATM1 = "atm-001-secreto-de-desarrollo-0123456789";
    static final String PAN = "4000000000001018";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MockRestServiceServer core;

    @Autowired
    private CircuitBreakerRegistry circuitos;

    @Autowired
    private ObjectMapper json;

    private String token;

    @BeforeEach
    void sesionYCircuitoCerrado() throws Exception {
        core.reset();
        circuito().reset();
        core.expect(requestTo(CORE + "/autenticacion/tarjetas")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(contrato("tarjeta-autenticada.json"), MediaType.APPLICATION_JSON));
        token = cuerpo(mvc.perform(firmado(post("/cajero/v1/sesiones"), "POST", "/cajero/v1/sesiones",
                "{\"numeroTarjeta\":\"" + PAN + "\",\"pin\":\"1234\"}", null)).andExpect(status().isCreated()))
                .get("tokenSesion").asText();
        core.verify();
        core.reset();
        // Solo cuentan las llamadas de cada prueba, no la de la sesion.
        circuito().reset();
    }

    @Test
    @DisplayName("La politica llega al circuito: ventana 6, minimo 3, 50 %, 10 s abierto")
    void politicaDelCircuito() {
        CircuitBreakerConfig politica = circuito().getCircuitBreakerConfig();
        assertThat(politica.getSlidingWindowSize()).isEqualTo(6);
        assertThat(politica.getMinimumNumberOfCalls()).isEqualTo(3);
        assertThat(politica.getFailureRateThreshold()).isEqualTo(50f);
        assertThat(politica.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(2);
    }

    @Test
    @DisplayName("Tres cortes de conexion abren el circuito; con el circuito abierto el retiro responde 503 (la solicitud no salio), no 504")
    void circuitoAbiertoEnRetiro() throws Exception {
        for (int i = 0; i < 3; i++) {
            core.expect(requestTo(CORE + "/cuentas/101/retiros")).andRespond(withException(new ConnectException("core caido")));
        }
        for (int i = 0; i < 3; i++) {
            verificar(retirar("clave-circuito-00" + i, 1000), 503, "SERVICIO_NO_DISPONIBLE", "DEVOLVER_TARJETA");
        }
        assertThat(circuito().getState()).isEqualTo(CircuitBreaker.State.OPEN);

        verificar(retirar("clave-circuito-009", 1000), 503, "SERVICIO_NO_DISPONIBLE", "DEVOLVER_TARJETA");
        core.verify();
        assertThat(circuito().getMetrics().getNumberOfNotPermittedCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("Un 5xx inesperado del core cuenta como falla: tres ERROR_INTERNO abren el circuito")
    void erroresDelServidorCuentan() throws Exception {
        for (int i = 0; i < 3; i++) {
            core.expect(requestTo(CORE + "/cuentas/101/retiros")).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON).body("{\"codigo\":\"ERROR_INTERNO\"}"));
        }
        for (int i = 0; i < 3; i++) {
            retirar("clave-interno-00" + i, 1000).andExpect(status().isGatewayTimeout());
        }
        assertThat(circuito().getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    @DisplayName("Respuestas de negocio (saldo insuficiente) y la cuenta ocupada no abren el circuito")
    void erroresDeNegocioNoCuentan() throws Exception {
        for (int i = 0; i < 4; i++) {
            core.expect(requestTo(CORE + "/cuentas/101/retiros")).andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(contrato("error-saldo-insuficiente.json")));
        }
        for (int i = 0; i < 3; i++) {
            core.expect(requestTo(CORE + "/cuentas/101/retiros")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON).body("{\"codigo\":\"CUENTA_OCUPADA\"}"));
        }
        for (int i = 0; i < 4; i++) {
            verificar(retirar("clave-negocio-00" + i, 1000), 422, "SALDO_INSUFICIENTE", "MOSTRAR_MENU");
        }
        for (int i = 0; i < 3; i++) {
            verificar(retirar("clave-ocupada-00" + i, 1000), 503, "CUENTA_OCUPADA", "REINTENTAR_MISMA_CLAVE");
        }
        assertThat(circuito().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuito().getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    @DisplayName("Un core lento pero sano (1,5 s) no se corta: el limite de tiempo del circuito queda sobre el read-timeout")
    void coreLentoNoSeCorta() throws Exception {
        ResponseCreator lento = solicitud -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                    .body(contrato("operacion-retiro.json")).createResponse(solicitud);
        };
        core.expect(requestTo(CORE + "/cuentas/101/retiros")).andRespond(lento);
        retirar("clave-lenta-000001", 3000).andExpect(status().isCreated());
    }

    private CircuitBreaker circuito() {
        return circuitos.circuitBreaker("core");
    }

    private ResultActions retirar(String clave, int monto) throws Exception {
        String cuerpo = "{\"monto\":" + monto + "}";
        return mvc.perform(firmado(post("/cajero/v1/retiros"), "POST", "/cajero/v1/retiros", cuerpo, clave)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private void verificar(ResultActions resultado, int estado, String codigo, String accion) throws Exception {
        JsonNode cuerpo = cuerpo(resultado.andExpect(status().is(estado)));
        assertThat(cuerpo.get("codigo").asText()).isEqualTo(codigo);
        assertThat(cuerpo.get("accion").asText()).isEqualTo(accion);
    }

    private static MockHttpServletRequestBuilder firmado(MockHttpServletRequestBuilder solicitud, String metodo, String ruta,
                                                         String cuerpo, String clave) {
        String marca = String.valueOf(Instant.now().getEpochSecond());
        String nonce = "circuito-" + UUID.randomUUID();
        solicitud.with(x509(PkiDePrueba.certificado("ATM-001")));
        solicitud.header("X-Terminal-Id", "ATM-001").header("X-Marca-Tiempo", marca).header("X-Nonce", nonce)
                .header("X-Firma", FirmaHmac.firmar(ATM1, metodo, ruta, marca, nonce, clave, cuerpo.getBytes(StandardCharsets.UTF_8)));
        if (clave != null) {
            solicitud.header("Idempotency-Key", clave);
        }
        return solicitud.contentType(MediaType.APPLICATION_JSON).content(cuerpo);
    }

    private JsonNode cuerpo(ResultActions resultado) throws Exception {
        return json.readTree(resultado.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static String contrato(String archivo) {
        try {
            return Files.readString(Path.of("contratos", "core-api", archivo));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
