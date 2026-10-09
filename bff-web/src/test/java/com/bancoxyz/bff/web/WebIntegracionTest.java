package com.bancoxyz.bff.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** BFF web: sesion del navegador, token relay, panel agregado con degradacion, CSRF y consola del ejecutivo. */
@SpringBootTest
@ActiveProfiles("prueba")
@AutoConfigureMockMvc
@AutoConfigureMockRestServiceServer
class WebIntegracionTest {

    static final String CUENTAS = "https://localhost:8100/api/v1";
    static final String CLIENTES = "https://localhost:8101/api/v1";
    static final String PAGOS = "https://localhost:8102/api/v1";
    static final String BEARER = "Bearer " + TokenDePrueba.TOKEN;
    static final String CUENTAS_DIANA = "[{\"cuentaId\":101,\"tipo\":\"ahorro\",\"saldoDisponible\":8040.00,\"estado\":\"ACTIVA\"},"
            + "{\"cuentaId\":120,\"tipo\":\"hipoteca\",\"saldoDisponible\":5045.00,\"estado\":\"ACTIVA\"}]";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MockRestServiceServer servicios;

    @Autowired
    private CircuitBreakerRegistry circuitos;

    @BeforeEach
    void limpiar() {
        servicios.reset();
        circuitos.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
    }

    /** Sesion iniciada en banco-auth: el id_token trae el usuario, su cliente y su rol. */
    private static RequestPostProcessor sesion(String usuario, Long clienteId, String rol) {
        return oidcLogin().idToken(t -> {
            t.subject(usuario).claim("usuario_id", 14).claim("rol", rol).claim("origen", "banco");
            if (clienteId != null) {
                t.claim("cliente_id", clienteId);
            }
        });
    }

    @Test
    @DisplayName("Sin sesion: /sesion dice autenticado=false y la API responde 401 con ProblemDetail (sin redirigir)")
    void sinSesion() throws Exception {
        mvc.perform(get("/web/api/sesion")).andExpect(status().isOk()).andExpect(jsonPath("$.autenticado").value(false));
        mvc.perform(get("/web/api/panel")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"))
                .andExpect(jsonPath("$.type").value("urn:bancoxyz:problema:no-autenticado"));
    }

    @Test
    @DisplayName("Panel: cuatro llamadas a tres servicios con el token del usuario, en una sola respuesta con totales")
    void panel() throws Exception {
        servicios.expect(requestTo(CUENTAS + "/clientes/4/cuentas")).andExpect(header(HttpHeaders.AUTHORIZATION, BEARER))
                .andRespond(withSuccess(CUENTAS_DIANA, MediaType.APPLICATION_JSON));
        servicios.expect(requestTo(CLIENTES + "/clientes/4")).andExpect(header(HttpHeaders.AUTHORIZATION, BEARER))
                .andRespond(withSuccess("{\"clienteId\":4,\"nombre\":\"Diana Prince\"}", MediaType.APPLICATION_JSON));
        servicios.expect(requestTo(PAGOS + "/pagos")).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        servicios.expect(requestTo(CLIENTES + "/clientes/4/avisos")).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        mvc.perform(get("/web/api/panel").with(sesion("diana.prince", 4L, "CLIENTE"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.cliente.nombre").value("Diana Prince"))
                .andExpect(jsonPath("$.disponible").value(8040.0)).andExpect(jsonPath("$.deuda").value(5045.0))
                .andExpect(jsonPath("$.cuentas.length()").value(2)).andExpect(jsonPath("$.noDisponibles.length()").value(0));
        servicios.verify();
    }

    @Test
    @DisplayName("Degradacion: si pagos-service y los avisos fallan el panel sale igual; si cuentas-service falla, 503")
    void degradacion() throws Exception {
        servicios.expect(requestTo(CUENTAS + "/clientes/4/cuentas")).andRespond(withSuccess(CUENTAS_DIANA, MediaType.APPLICATION_JSON));
        servicios.expect(requestTo(CLIENTES + "/clientes/4")).andRespond(withSuccess("{\"nombre\":\"Diana Prince\"}", MediaType.APPLICATION_JSON));
        servicios.expect(requestTo(PAGOS + "/pagos")).andRespond(withServerError());
        servicios.expect(requestTo(CLIENTES + "/clientes/4/avisos")).andRespond(withServerError());
        mvc.perform(get("/web/api/panel").with(sesion("diana.prince", 4L, "CLIENTE"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.noDisponibles[0]").value("pagos")).andExpect(jsonPath("$.noDisponibles[1]").value("avisos"))
                .andExpect(jsonPath("$.disponible").value(8040.0));

        servicios.reset();
        servicios.expect(manyTimes(), requestTo(CUENTAS + "/clientes/4/cuentas")).andRespond(withServerError());
        mvc.perform(get("/web/api/panel").with(sesion("diana.prince", 4L, "CLIENTE"))).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.codigo").value("SERVICIO_NO_DISPONIBLE"));
    }

    @Test
    @DisplayName("Un pago exige el token CSRF; con el, se reenvia con su Idempotency-Key y el 202 de pagos-service")
    void pagoConCsrf() throws Exception {
        mvc.perform(post("/web/api/pagos/transferencias").with(sesion("diana.prince", 4L, "CLIENTE"))
                        .header("Idempotency-Key", "web-0001").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cuentaOrigen\":101,\"cuentaDestino\":102,\"monto\":1000}"))
                .andExpect(status().isForbidden());
        servicios.expect(requestTo(PAGOS + "/pagos/transferencias")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", "web-0001")).andExpect(header(HttpHeaders.AUTHORIZATION, BEARER))
                .andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"id\":\"p-1\",\"estado\":\"SOLICITADO\"}"));
        mvc.perform(post("/web/api/pagos/transferencias").with(sesion("diana.prince", 4L, "CLIENTE")).with(csrf())
                        .header("Idempotency-Key", "web-0001").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cuentaOrigen\":101,\"cuentaDestino\":102,\"monto\":1000}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.estado").value("SOLICITADO"));
        servicios.verify();
    }

    @Test
    @DisplayName("Error de negocio de un microservicio: llega al navegador con el mismo estado y codigo")
    void errorDeNegocio() throws Exception {
        servicios.expect(requestTo(PAGOS + "/pagos/transferencias")).andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON).body("{\"codigo\":\"MISMA_CUENTA\",\"status\":422}"));
        mvc.perform(post("/web/api/pagos/transferencias").with(sesion("diana.prince", 4L, "CLIENTE")).with(csrf())
                        .header("Idempotency-Key", "web-0002").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cuentaOrigen\":101,\"cuentaDestino\":101,\"monto\":10}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.codigo").value("MISMA_CUENTA"));
    }

    @Test
    @DisplayName("Consola del ejecutivo: un cliente recibe 403; el ejecutivo lista clientes con su token")
    void consolaDelEjecutivo() throws Exception {
        mvc.perform(get("/web/api/ejecutivo/clientes").with(sesion("diana.prince", 4L, "CLIENTE")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.codigo").value("ACCESO_DENEGADO"));
        servicios.expect(requestTo(CLIENTES + "/clientes")).andExpect(header(HttpHeaders.AUTHORIZATION, BEARER))
                .andRespond(withSuccess("[{\"clienteId\":1,\"nombre\":\"Alice Brown\"}]", MediaType.APPLICATION_JSON));
        mvc.perform(get("/web/api/ejecutivo/clientes").with(sesion("ejecutivo", null, "EJECUTIVO")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].nombre").value("Alice Brown"));
    }

    @Test
    @DisplayName("La pagina y sus recursos son publicos; la cabecera CSP impide scripts de otros origenes")
    void paginaPublica() throws Exception {
        mvc.perform(get("/index.html")).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Security-Policy", "default-src 'self'; frame-ancestors 'none'"));
        mvc.perform(get("/web/api/sesion").with(sesion("diana.prince", 4L, "CLIENTE")))
                .andExpect(jsonPath("$.rol").value("CLIENTE")).andExpect(jsonPath("$.clienteId").value(4));
    }
}
