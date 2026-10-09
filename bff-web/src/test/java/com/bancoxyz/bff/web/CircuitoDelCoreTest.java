package com.bancoxyz.bff.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.ConnectException;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.client.AutoConfigureMockRestServiceServer;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Tolerancia a fallos del canal web con la politica del circuito que trae el BFF: la misma que entrega el Config Server
 * (banco-xyz-cloud/configuracion/bff-web.properties), comprobado por banco-xyz-cloud/scripts/verificar_coherencia.sh.
 */
@ActiveProfiles("prueba")
@SpringBootTest(properties = "spring.jackson.deserialization.fail-on-missing-creator-properties=true")
@AutoConfigureMockMvc
@AutoConfigureMockRestServiceServer
class CircuitoDelCoreTest extends PruebaWeb {

    @Autowired
    private MockRestServiceServer servidor;

    @Autowired
    private CircuitBreakerRegistry circuitos;

    @Override
    protected MockRestServiceServer core() {
        return servidor;
    }

    @BeforeEach
    void circuitoCerrado() {
        servidor.reset();
        circuitos.circuitBreaker("core").reset();
    }

    @Test
    @DisplayName("Tres cortes de conexion abren el circuito; el cuarto login no llega al core y responde 503 de inmediato")
    void abreYCortaElPaso() throws Exception {
        for (int i = 0; i < 3; i++) {
            servidor.expect(requestTo(CORE + "/autenticacion/usuarios")).andRespond(withException(new ConnectException("core caido")));
        }
        for (int i = 0; i < 3; i++) {
            login().andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.codigo").value("SERVICIO_NO_DISPONIBLE"));
        }
        assertThat(circuitos.circuitBreaker("core").getState()).isEqualTo(CircuitBreaker.State.OPEN);

        login().andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.codigo").value("SERVICIO_NO_DISPONIBLE"));
        servidor.verify();
        assertThat(circuitos.circuitBreaker("core").getMetrics().getNumberOfNotPermittedCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("Credenciales invalidas no abren el circuito; tres ERROR_INTERNO del core si")
    void soloLasFallasDelServidorCuentan() throws Exception {
        for (int i = 0; i < 3; i++) {
            servidor.expect(requestTo(CORE + "/autenticacion/usuarios")).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON).body("{\"codigo\":\"CREDENCIALES_INVALIDAS\"}"));
        }
        for (int i = 0; i < 3; i++) {
            login().andExpect(status().isUnauthorized()).andExpect(jsonPath("$.codigo").value("CREDENCIALES_INVALIDAS"));
        }
        assertThat(circuitos.circuitBreaker("core").getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        servidor.reset();
        for (int i = 0; i < 3; i++) {
            servidor.expect(requestTo(CORE + "/autenticacion/usuarios")).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON).body("{\"codigo\":\"ERROR_INTERNO\"}"));
        }
        for (int i = 0; i < 3; i++) {
            login();
        }
        assertThat(circuitos.circuitBreaker("core").getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    private ResultActions login() throws Exception {
        return mvc.perform(post("/web/api/sesion").contentType(MediaType.APPLICATION_JSON)
                .content("{\"usuario\":\"jane.smith\",\"password\":\"Cliente2026!\"}"));
    }
}
