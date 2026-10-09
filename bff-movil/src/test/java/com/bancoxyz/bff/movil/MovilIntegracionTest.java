package com.bancoxyz.bff.movil;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import com.bancoxyz.bff.movil.proxy.ProxyCoreConCache;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.MockServerRestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.test.web.client.UnorderedRequestExpectationManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

@ActiveProfiles("prueba")
@SpringBootTest(properties = "spring.jackson.deserialization.fail-on-missing-creator-properties=true")
@AutoConfigureMockMvc
@Import(MovilIntegracionTest.CoreSinOrden.class)
class MovilIntegracionTest {

    /** La portada pide los movimientos de cada cuenta en paralelo: el orden de esas llamadas no es fijo. */
    @TestConfiguration(proxyBeanMethods = false)
    static class CoreSinOrden {
        @Bean
        MockServerRestClientCustomizer coreSinOrden() {
            return new MockServerRestClientCustomizer(UnorderedRequestExpectationManager.class);
        }
    }

    static final String CORE = "https://localhost:8080/api/v1";
    static final String DISPOSITIVO = "iphone-de-jane-01";

    @Autowired
    private MockMvc mvc;

    /** Las pruebas de traduccion encadenan errores del core a proposito: cada llamada parte con el circuito cerrado. */
    @Autowired
    private CircuitBreakerRegistry circuitos;

    private void cerrarCircuitos() {
        circuitos.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
    }

    @Autowired
    private MockServerRestClientCustomizer simulador;

    private MockRestServiceServer core;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JwtEncoder codificador;

    @Autowired
    private ProxyCoreConCache cache;

    record Tokens(String access, String refresh) {
    }

    @BeforeEach
    void vaciarCache() {
        // Sin @AutoConfigureMockRestServiceServer nadie reinicia el simulador entre pruebas: se hace aqui.
        core = simulador.getServer();
        core.reset();
        cache.vaciar();
    }

    @Test
    @DisplayName("Portada ligera: primer nombre, disponible y deuda separados, 5 ultimos de 3 campos, sin nulos ni datos del batch")
    void portada() throws Exception {
        Tokens tokens = iniciarSesion();
        esperarPortada();
        MvcResult resultado = portada(tokens.access(), DISPOSITIVO).andExpect(status().isOk()).andReturn();
        JsonNode inicio = json.readTree(resultado.getResponse().getContentAsString(StandardCharsets.UTF_8));

        assertThat(inicio.get("nombre").asText()).isEqualTo("Jane");
        assertThat(inicio.get("disponible").decimalValue()).isEqualByComparingTo("12060");
        assertThat(inicio.get("deuda").decimalValue()).isEqualByComparingTo("8120");
        assertThat(inicio.get("cuentas")).hasSize(2);
        assertThat(inicio.get("cuentas").get(0).size()).isEqualTo(3);
        assertThat(inicio.get("ultimos").size()).isLessThanOrEqualTo(5);
        JsonNode movimiento = inicio.get("ultimos").get(0);
        assertThat(movimiento.size()).isEqualTo(3);
        assertThat(movimiento.has("fecha")).isTrue();
        assertThat(movimiento.get("desc").asText().length()).isLessThanOrEqualTo(24);
        String texto = resultado.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(texto).doesNotContain("observacion", "anomalia", "tasaMensual", "null");
        assertThat(resultado.getResponse().getHeader(HttpHeaders.ETAG)).startsWith("W/\"");
    }

    @Test
    @DisplayName("ETag debil: la misma portada con If-None-Match responde 304 sin cuerpo, y el proxy no vuelve a pedir ni las cuentas ni los movimientos")
    void etagYProxy() throws Exception {
        Tokens tokens = iniciarSesion();
        core.reset();
        core.expect(requestTo(CORE + "/clientes/6/cuentas")).andRespond(withSuccess(contrato("cuentas.json"), MediaType.APPLICATION_JSON));
        esperarMovimientosDePortada();
        String etag = portada(tokens.access(), DISPOSITIVO).andExpect(status().isOk()).andReturn().getResponse().getHeader(HttpHeaders.ETAG);
        MvcResult segunda = mvc.perform(get("/movil/v1/inicio").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.access())
                .header("X-Dispositivo-Id", DISPOSITIVO).header(HttpHeaders.IF_NONE_MATCH, etag))
                .andExpect(status().isNotModified()).andReturn();
        assertThat(segunda.getResponse().getContentAsByteArray()).isEmpty();
        core.verify();
    }

    @Test
    @DisplayName("Movimientos por cursor: siguiente mientras quedan paginas, ausente en la ultima; limite y cursor validados")
    void cursor() throws Exception {
        Tokens tokens = iniciarSesion();
        core.reset();
        core.expect(requestTo(CORE + "/cuentas/101/movimientos?pagina=0&tamano=2")).andExpect(header("X-Usuario-Id", "3"))
                .andRespond(withSuccess(contrato("pagina-movimientos.json").replace("\"totalPaginas\": 3", "\"totalPaginas\": 2"),
                        MediaType.APPLICATION_JSON));
        core.expect(requestTo(CORE + "/cuentas/101/movimientos?pagina=1&tamano=2"))
                .andRespond(withSuccess(contrato("pagina-movimientos.json").replace("\"totalPaginas\": 3", "\"totalPaginas\": 2"),
                        MediaType.APPLICATION_JSON));
        JsonNode primera = cuerpo(movimientos(tokens, "?limite=2").andExpect(status().isOk()));
        assertThat(primera.get("items")).hasSize(2);
        String siguiente = primera.get("siguiente").asText();
        JsonNode ultima = cuerpo(movimientos(tokens, "?cursor=" + siguiente).andExpect(status().isOk()));
        assertThat(ultima.has("siguiente")).isFalse();

        assertThat(cuerpo(movimientos(tokens, "?limite=21").andExpect(status().isBadRequest())).get("codigo").asText())
                .isEqualTo("SOLICITUD_INVALIDA");
        movimientos(tokens, "?cursor=no-es-un-cursor").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Sin token, token basura, otro dispositivo, vencido, de otra audiencia o de otro secreto: 401 con {codigo, mensaje}")
    void seguridadDelToken() throws Exception {
        assertThat(cuerpo(mvc.perform(get("/movil/v1/inicio")).andExpect(status().isUnauthorized())).get("codigo").asText())
                .isEqualTo("NO_AUTENTICADO");
        assertThat(cuerpo(portada("no.es.un.jwt", DISPOSITIVO).andExpect(status().isUnauthorized())).get("codigo").asText())
                .isEqualTo("TOKEN_INVALIDO");
        Tokens tokens = iniciarSesion();
        assertThat(cuerpo(portada(tokens.access(), "android-robado").andExpect(status().isUnauthorized())).get("codigo").asText())
                .isEqualTo("DISPOSITIVO_NO_COINCIDE");
        portada(tokenManual(codificador, Instant.now().minusSeconds(310), Instant.now().minusSeconds(10), "app-movil"), DISPOSITIVO)
                .andExpect(status().isUnauthorized());
        portada(tokenManual(codificador, Instant.now(), Instant.now().plusSeconds(60), "cajero"), DISPOSITIVO)
                .andExpect(status().isUnauthorized());
        JwtEncoder otroSecreto = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(
                "otro-secreto-de-otro-canal-con-32-bytes-o-mas".getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
        portada(tokenManual(otroSecreto, Instant.now(), Instant.now().plusSeconds(60), "app-movil"), DISPOSITIVO)
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Refresh rotativo: renovar consume el token; reutilizarlo revoca la familia completa, incluido el access vigente")
    void renovacionYReutilizacion() throws Exception {
        Tokens primero = iniciarSesion();
        Tokens segundo = renovar(primero.refresh(), DISPOSITIVO).andExpect(status().isCreated()).andReturn()
                .getResponse().getContentAsString().transform(this::tokens);
        assertThat(segundo.refresh()).isNotEqualTo(primero.refresh());

        assertThat(cuerpo(renovar(primero.refresh(), DISPOSITIVO).andExpect(status().isUnauthorized())).get("codigo").asText())
                .isEqualTo("REFRESH_REUTILIZADO");
        assertThat(cuerpo(renovar(segundo.refresh(), DISPOSITIVO).andExpect(status().isUnauthorized())).get("codigo").asText())
                .isEqualTo("REFRESH_INVALIDO");
        assertThat(cuerpo(portada(segundo.access(), DISPOSITIVO).andExpect(status().isUnauthorized())).get("codigo").asText())
                .isEqualTo("SESION_REVOCADA");
    }

    @Test
    @DisplayName("Renovar desde otro dispositivo o con un refresh desconocido: 401; cerrar sesion revoca el access token")
    void cierreYOtroDispositivo() throws Exception {
        Tokens tokens = iniciarSesion();
        assertThat(cuerpo(renovar(tokens.refresh(), "otro-telefono").andExpect(status().isUnauthorized())).get("codigo").asText())
                .isEqualTo("DISPOSITIVO_NO_COINCIDE");
        renovar("refresh-inventado", DISPOSITIVO).andExpect(status().isUnauthorized());
        mvc.perform(delete("/movil/v1/sesiones").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.access())
                .header("X-Dispositivo-Id", DISPOSITIVO)).andExpect(status().isNoContent());
        assertThat(cuerpo(portada(tokens.access(), DISPOSITIVO).andExpect(status().isUnauthorized())).get("codigo").asText())
                .isEqualTo("SESION_REVOCADA");
    }

    @Test
    @DisplayName("Traduccion de errores: codigos conocidos pasan, desconocidos 502, core caido 503, cuerpo invalido 400")
    void traduccionDeErrores() throws Exception {
        esperarLogin(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_PROBLEM_JSON).body("{\"codigo\":\"ROL_NO_PERMITIDO_EN_CANAL\"}"));
        esperarLogin(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_PROBLEM_JSON).body("{\"codigo\":\"CREDENCIALES_INVALIDAS\"}"));
        esperarLogin(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_PROBLEM_JSON).body("{\"codigo\":\"NO_AUTENTICADO\"}"));
        esperarLogin(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("<html>error</html>"));
        esperarLogin(withException(new ConnectException("Connection refused")));
        verificar(login("ejecutivo"), 403, "ROL_NO_PERMITIDO_EN_CANAL");
        verificar(login("jane.smith"), 401, "CREDENCIALES_INVALIDAS");
        verificar(login("jane.smith"), 502, "ERROR_CORE");
        verificar(login("jane.smith"), 502, "ERROR_CORE");
        verificar(login("jane.smith"), 503, "SERVICIO_NO_DISPONIBLE");
        verificar(mvc.perform(post("/movil/v1/sesiones").contentType(MediaType.APPLICATION_JSON)
                .content("{\"usuario\":\"jane.smith\",\"password\":\"x\"}")), 400, "SOLICITUD_INVALIDA");

        core.reset();
        cerrarCircuitos();
        Tokens tokens = iniciarSesion();
        core.reset();
        core.expect(requestTo(CORE + "/cuentas/140/movimientos?pagina=0&tamano=10"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(contrato("error-cuenta-no-encontrada.json")));
        verificar(mvc.perform(get("/movil/v1/cuentas/140/movimientos").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.access())
                .header("X-Dispositivo-Id", DISPOSITIVO)), 404, "CUENTA_NO_ENCONTRADA");
    }

    @Test
    @DisplayName("Un usuario sin cliente (ejecutivo) que el core dejara pasar tampoco entra a la app")
    void usuarioSinCliente() throws Exception {
        esperarLogin(withSuccess(contrato("usuario-autenticado.json").replace("\"clienteId\": 6", "\"clienteId\": null"),
                MediaType.APPLICATION_JSON));
        verificar(login("ejecutivo"), 403, "ROL_NO_PERMITIDO_EN_CANAL");
    }

    // ---------------------------------------------------------------------------------------------

    private Tokens iniciarSesion() throws Exception {
        esperarLogin(withSuccess(contrato("usuario-autenticado.json"), MediaType.APPLICATION_JSON));
        MvcResult resultado = login("jane.smith").andExpect(status().isCreated()).andReturn();
        JsonNode par = json.readTree(resultado.getResponse().getContentAsString());
        assertThat(par.get("tipo").asText()).isEqualTo("Bearer");
        assertThat(par.get("expiraEn").asInt()).isEqualTo(300);
        return tokens(resultado.getResponse().getContentAsString());
    }

    private Tokens tokens(String cuerpo) {
        try {
            JsonNode par = json.readTree(cuerpo);
            return new Tokens(par.get("accessToken").asText(), par.get("refreshToken").asText());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void esperarLogin(ResponseCreator respuesta) {
        core.expect(requestTo(CORE + "/autenticacion/usuarios")).andExpect(method(HttpMethod.POST)).andRespond(respuesta);
    }

    private void esperarPortada() throws IOException {
        core.reset();
        core.expect(requestTo(CORE + "/clientes/6/cuentas")).andExpect(header("X-Usuario-Id", "3"))
                .andRespond(withSuccess(contrato("cuentas.json"), MediaType.APPLICATION_JSON));
        esperarMovimientosDePortada();
    }

    private void esperarMovimientosDePortada() throws IOException {
        core.expect(requestTo(CORE + "/cuentas/101/movimientos?pagina=0&tamano=5"))
                .andRespond(withSuccess(contrato("pagina-movimientos.json"), MediaType.APPLICATION_JSON));
        core.expect(requestTo(CORE + "/cuentas/117/movimientos?pagina=0&tamano=5"))
                .andRespond(withSuccess(contrato("pagina-movimientos.json"), MediaType.APPLICATION_JSON));
    }

    private ResultActions login(String usuario) throws Exception {
        cerrarCircuitos();
        return mvc.perform(post("/movil/v1/sesiones").contentType(MediaType.APPLICATION_JSON)
                .content("{\"usuario\":\"" + usuario + "\",\"password\":\"Cliente2026!\",\"dispositivoId\":\"" + DISPOSITIVO + "\"}"));
    }

    private ResultActions renovar(String refresh, String dispositivo) throws Exception {
        return mvc.perform(post("/movil/v1/sesiones/renovar").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refresh + "\",\"dispositivoId\":\"" + dispositivo + "\"}"));
    }

    private ResultActions portada(String access, String dispositivo) throws Exception {
        return mvc.perform(get("/movil/v1/inicio").header(HttpHeaders.AUTHORIZATION, "Bearer " + access)
                .header("X-Dispositivo-Id", dispositivo));
    }

    private ResultActions movimientos(Tokens tokens, String query) throws Exception {
        return mvc.perform(get("/movil/v1/cuentas/101/movimientos" + query)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.access()).header("X-Dispositivo-Id", DISPOSITIVO));
    }

    private void verificar(ResultActions resultado, int estado, String codigo) throws Exception {
        JsonNode error = cuerpo(resultado.andExpect(status().is(estado)));
        assertThat(error.get("codigo").asText()).isEqualTo(codigo);
        assertThat(error.get("mensaje").asText()).isNotBlank();
    }

    private static String tokenManual(JwtEncoder encoder, Instant emitido, Instant vence, String audiencia) {
        JwtClaimsSet reclamos = JwtClaimsSet.builder().issuer("bff-movil").audience(List.of(audiencia)).subject("3")
                .issuedAt(emitido).expiresAt(vence).id(UUID.randomUUID().toString()).claim("cliente", 6)
                .claim("nombre", "Jane Smith").claim("dispositivo", DISPOSITIVO).claim("fam", "familia-manual").build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), reclamos)).getTokenValue();
    }

    private JsonNode cuerpo(ResultActions resultado) throws Exception {
        return json.readTree(resultado.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static String contrato(String archivo) throws IOException {
        return Files.readString(Path.of("contratos", "core-api", archivo));
    }
}
