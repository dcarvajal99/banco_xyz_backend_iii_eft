package com.bancoxyz.bff.movil;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import com.bancoxyz.bff.movil.proxy.ProxyCoreConCache;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;


/**
 * App movil con el token de banco-auth (cliente publico banca-movil): portada ligera, cursor, ETag, transferencia y
 * token relay hacia los microservicios. Los microservicios se simulan.
 */
@ActiveProfiles("prueba")
// Portada en serie (hilos-portada=0): el orden de las llamadas simuladas es fijo. El paralelismo se mide aparte.
@SpringBootTest(properties = {"spring.jackson.deserialization.fail-on-missing-creator-properties=true",
        "banco.movil.hilos-portada=0"})
@AutoConfigureMockMvc
@AutoConfigureMockRestServiceServer
class MovilIntegracionTest {

    static final String CUENTAS = "https://localhost:8100/api/v1";
    static final String PAGOS = "https://localhost:8102/api/v1";
    static final String TOKEN = "token-de-jane";

    @Autowired
    private MockMvc mvc;


    @Autowired
    private CircuitBreakerRegistry circuitos;

    @Autowired
    private ProxyCoreConCache cache;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private MockRestServiceServer servicios;

    @BeforeEach
    void limpiar() {
        circuitos.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
        cache.vaciar();
        servicios.reset();
    }

    /** Token de Jane emitido para la app (validado por el decodificador real en produccion). */
    private static RequestPostProcessor jane(String... scopes) {
        return jwt().jwt(j -> j.tokenValue(TOKEN).subject("jane.smith").claim("usuario_id", 3).claim("cliente_id", 6)
                        .claim("rol", "CLIENTE").claim("azp", "banca-movil").claim("scope", List.of(scopes)))
                .authorities(java.util.Arrays.stream(scopes).map(s -> new SimpleGrantedAuthority("SCOPE_" + s))
                        .toArray(SimpleGrantedAuthority[]::new));
    }

    private void esperarPortada() throws IOException {
        servicios.reset();
        servicios.expect(requestTo(CUENTAS + "/clientes/6/cuentas"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andRespond(withSuccess(contrato("cuentas.json"), MediaType.APPLICATION_JSON));
        servicios.expect(requestTo(CUENTAS + "/cuentas/101/movimientos?pagina=0&tamano=5"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andRespond(withSuccess(contrato("pagina-movimientos.json"), MediaType.APPLICATION_JSON));
        servicios.expect(requestTo(CUENTAS + "/cuentas/117/movimientos?pagina=0&tamano=5"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andRespond(withSuccess(contrato("pagina-movimientos.json"), MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("Portada ligera con el token del usuario reenviado a cuentas-service (tambien desde los hilos paralelos)")
    void portada() throws Exception {
        esperarPortada();
        MvcResult resultado = mvc.perform(get("/movil/v1/inicio").with(jane("cuentas.leer"))).andExpect(status().isOk()).andReturn();
        JsonNode inicio = json.readTree(resultado.getResponse().getContentAsString(StandardCharsets.UTF_8));

        assertThat(inicio.get("nombre").asText()).isEqualTo("Jane");
        assertThat(inicio.get("cuentas")).hasSize(2);
        assertThat(inicio.get("cuentas").get(0).size()).isEqualTo(3);
        assertThat(inicio.get("ultimos").size()).isLessThanOrEqualTo(5);
        assertThat(resultado.getResponse().getContentAsString()).doesNotContain("observacion", "anomalia", "tasaMensual", "null");
        assertThat(resultado.getResponse().getHeader(HttpHeaders.ETAG)).startsWith("W/\"");
        servicios.verify();
    }

    @Test
    @DisplayName("ETag: la misma portada con If-None-Match responde 304 sin cuerpo y el proxy no vuelve a llamar")
    void etag() throws Exception {
        esperarPortada();
        String etag = mvc.perform(get("/movil/v1/inicio").with(jane("cuentas.leer"))).andReturn().getResponse()
                .getHeader(HttpHeaders.ETAG);
        mvc.perform(get("/movil/v1/inicio").with(jane("cuentas.leer")).header(HttpHeaders.IF_NONE_MATCH, etag))
                .andExpect(status().isNotModified());
        servicios.verify();
    }

    @Test
    @DisplayName("Transferencia: el BFF reenvia el token y la Idempotency-Key a pagos-service y responde solo id y estado")
    void transferencia() throws Exception {
        servicios.reset();
        servicios.expect(requestTo(PAGOS + "/pagos/transferencias")).andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)).andExpect(header("Idempotency-Key", "app-0001"))
                .andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON).body(
                        "{\"id\":\"p-1\",\"operacion\":\"TRANSFERENCIA\",\"estado\":\"SOLICITADO\",\"motivo\":null,\"monto\":1000,"
                                + "\"historial\":[],\"canal\":\"MOVIL\"}"));
        mvc.perform(post("/movil/v1/transferencias").with(jane("pagos.escribir")).header("Idempotency-Key", "app-0001")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"origen\":101,\"destino\":117,\"monto\":1000}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.id").value("p-1"))
                .andExpect(jsonPath("$.estado").value("SOLICITADO")).andExpect(jsonPath("$.motivo").doesNotExist());
        servicios.verify();
    }

    @Test
    @DisplayName("Sin token: 401; sin el scope: 403; los dos con el ProblemDetail comun")
    void seguridad() throws Exception {
        mvc.perform(get("/movil/v1/inicio")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"))
                .andExpect(jsonPath("$.type").value("urn:bancoxyz:problema:no-autenticado"));
        mvc.perform(post("/movil/v1/transferencias").with(jane("cuentas.leer")).header("Idempotency-Key", "x")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"origen\":1,\"destino\":2,\"monto\":1}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.codigo").value("ACCESO_DENEGADO"));
    }

    @Test
    @DisplayName("Errores de los servicios: codigo conocido pasa con texto para la app; caido: 503; cuerpo invalido: 400")
    void errores() throws Exception {
        servicios.reset();
        servicios.expect(requestTo(PAGOS + "/pagos/transferencias")).andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON).body("{\"codigo\":\"MISMA_CUENTA\"}"));
        mvc.perform(post("/movil/v1/transferencias").with(jane("pagos.escribir")).header("Idempotency-Key", "app-0002")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"origen\":101,\"destino\":101,\"monto\":10}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.codigo").value("MISMA_CUENTA"))
                .andExpect(jsonPath("$.detail").value("Elija dos cuentas distintas."));
        servicios.reset();
        servicios.expect(requestTo(CUENTAS + "/clientes/6/cuentas")).andRespond(withServerError());
        mvc.perform(get("/movil/v1/inicio").with(jane("cuentas.leer"))).andExpect(status().is5xxServerError());
        mvc.perform(post("/movil/v1/transferencias").with(jane("pagos.escribir")).header("Idempotency-Key", "app-0003")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"origen\":101}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"));
    }

    @Test
    @DisplayName("Solo tokens emitidos para la app (azp=banca-movil): uno de la banca web no abre la API movil")
    void soloTokensDeLaApp() {
        Jwt deLaWeb = Jwt.withTokenValue("t").header("alg", "RS256").claim("azp", "bff-web").issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60)).build();
        Jwt deLaApp = Jwt.withTokenValue("t").header("alg", "RS256").claim("azp", "banca-movil").issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60)).build();
        assertThat(ConfiguracionSeguridadAcceso.azp().validate(deLaWeb).hasErrors()).isTrue();
        assertThat(ConfiguracionSeguridadAcceso.azp().validate(deLaApp).hasErrors()).isFalse();
    }

    private static String contrato(String archivo) throws IOException {
        return Files.readString(Path.of("contratos", "core-api", archivo));
    }
}
