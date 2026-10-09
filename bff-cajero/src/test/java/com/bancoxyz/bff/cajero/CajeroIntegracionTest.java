package com.bancoxyz.bff.cajero;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.x509;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.bancoxyz.bff.cajero.seguridad.FiltroCertificadoDeTerminal;
import com.bancoxyz.bff.cajero.seguridad.FirmaHmac;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.client.AutoConfigureMockRestServiceServer;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** El BFF del cajero completo: firma, sesion, traduccion y formato, con el core simulado desde los contratos. */
@ActiveProfiles("prueba")
@SpringBootTest(properties = "spring.jackson.deserialization.fail-on-missing-creator-properties=true")
@AutoConfigureMockMvc
@AutoConfigureMockRestServiceServer
class CajeroIntegracionTest {

    static final String CORE = "https://localhost:8100/api/v1";
    static final String ATM1 = "atm-001-secreto-de-desarrollo-0123456789";
    static final String ATM2 = "atm-002-secreto-de-desarrollo-9876543210";
    static final String PAN = "4000000000001018";

    @Autowired
    private MockMvc mvc;

    /** Las pruebas de traduccion encadenan errores del core a proposito: cada llamada parte con el circuito cerrado. */
    @Autowired
    private CircuitBreakerRegistry circuitos;

    private void cerrarCircuitos() {
        circuitos.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
    }

    @Autowired
    private MockRestServiceServer core;

    @Autowired
    private JwtDecoder decodificador;

    @Autowired
    private JwtEncoder codificador;

    @Autowired
    private ObjectMapper json;

    @Test
    @DisplayName("Sesion con tarjeta y PIN: token de 120 s ligado al terminal, la cuenta y la tarjeta")
    void sesionValida() throws Exception {
        String token = abrirSesion();
        Jwt jwt = decodificador.decode(token);
        assertThat(jwt.getSubject()).isEqualTo("12");
        assertThat(jwt.getClaimAsString("terminal")).isEqualTo("ATM-001");
        assertThat(((Number) jwt.getClaim("cuenta")).longValue()).isEqualTo(101);
        assertThat(jwt.getAudience()).containsExactly("cajero");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("bff-cajero");
        assertThat(jwt.getExpiresAt().getEpochSecond() - jwt.getIssuedAt().getEpochSecond()).isEqualTo(120);
    }

    @Test
    @DisplayName("PAN que no pasa Luhn o PIN mal formado: 400 sin gastar un intento en el core")
    void datosDeTarjetaInvalidos() throws Exception {
        sesion("{\"numeroTarjeta\":\"4000000000001019\",\"pin\":\"1234\"}").andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("DATOS_TARJETA_INVALIDOS")));
        sesion("{\"numeroTarjeta\":\"" + PAN + "\",\"pin\":\"12a4\"}").andExpect(status().isBadRequest());
        sesion("").andExpect(status().isBadRequest());
        core.verify();
    }

    @Test
    @DisplayName("PIN incorrecto, tarjeta bloqueada, tarjeta invalida y core caido, con la accion para el hardware")
    void erroresDeSesion() throws Exception {
        responderSesion(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(contrato("error-pin-incorrecto.json")));
        JsonNode pin = cuerpo(sesion(credenciales()).andExpect(status().isUnauthorized()));
        assertThat(pin.get("accion").asText()).isEqualTo("SOLICITAR_PIN");
        assertThat(pin.get("intentosRestantes").asInt()).isEqualTo(2);
        assertThat(pin.get("mensajePantalla").asText()).isEqualTo("PIN incorrecto. Le quedan 2 intentos.");

        core.reset();
        responderSesion(withStatus(HttpStatus.LOCKED).contentType(MediaType.APPLICATION_PROBLEM_JSON).body("{\"codigo\":\"TARJETA_BLOQUEADA\"}"));
        assertThat(cuerpo(sesion(credenciales()).andExpect(status().isLocked())).get("accion").asText()).isEqualTo("RETENER_TARJETA");

        core.reset();
        responderSesion(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_PROBLEM_JSON).body("{\"codigo\":\"TARJETA_INVALIDA\"}"));
        assertThat(cuerpo(sesion(credenciales()).andExpect(status().isUnauthorized())).get("accion").asText()).isEqualTo("DEVOLVER_TARJETA");

        core.reset();
        responderSesion(withException(new ConnectException("Connection refused")));
        assertThat(cuerpo(sesion(credenciales()).andExpect(status().isServiceUnavailable())).get("codigo").asText())
                .isEqualTo("SERVICIO_NO_DISPONIBLE");
    }

    @Test
    @DisplayName("Saldo: el core recibe tarjeta y terminal; la pantalla recibe un texto con formato chileno y nada mas")
    void saldo() throws Exception {
        String token = abrirSesion();
        core.reset();
        core.expect(requestTo(CORE + "/cuentas/101")).andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Tarjeta-Id", "12")).andExpect(header("X-Terminal-Id", "ATM-001"))
                .andRespond(withSuccess(contrato("cuenta.json"), MediaType.APPLICATION_JSON));
        JsonNode saldo = cuerpo(mvc.perform(firmado(get("/cajero/v1/saldo"), "GET", "/cajero/v1/saldo", "", null, "ATM-001", ATM1)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andExpect(status().isOk()));
        assertThat(saldo.get("mensajePantalla").asText()).isEqualTo("Su saldo disponible es $12.060");
        assertThat(saldo.get("moneda").asText()).isEqualTo("CLP");
        assertThat(saldo.has("titular")).isFalse();
        assertThat(saldo.has("tasaMensual")).isFalse();
    }

    @Test
    @DisplayName("Firma y sesion obligatorias: sin firma, sin token, token de otro terminal, adulterado, vencido o de otro canal")
    void firmaYSesionObligatorias() throws Exception {
        String token = abrirSesion();
        mvc.perform(get("/cajero/v1/saldo").with(x509(PkiDePrueba.certificado("ATM-001"))).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized()).andExpect(content().string(org.hamcrest.Matchers.containsString("FIRMA_INVALIDA")));
        consultarSaldo(null, "ATM-001", ATM1).andExpect(status().isUnauthorized())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("SESION_FINALIZADA")));
        consultarSaldo(token, "ATM-002", ATM2).andExpect(status().isUnauthorized())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("SESION_DE_OTRO_TERMINAL")));
        consultarSaldo(token.substring(0, token.length() - 3) + "abc", "ATM-001", ATM1).andExpect(status().isUnauthorized());
        consultarSaldo(tokenManual(Instant.now().minusSeconds(130), Instant.now().minusSeconds(10), "cajero"), "ATM-001", ATM1)
                .andExpect(status().isUnauthorized());
        consultarSaldo(tokenManual(Instant.now(), Instant.now().plusSeconds(60), "app-movil"), "ATM-001", ATM1)
                .andExpect(status().isUnauthorized());

        mvc.perform(firmado(delete("/cajero/v1/sesiones"), "DELETE", "/cajero/v1/sesiones", "", null, "ATM-001", ATM1)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("DEVOLVER_TARJETA")));
        consultarSaldo(token, "ATM-001", ATM1).andExpect(status().isUnauthorized())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("SESION_FINALIZADA")));
    }

    @Test
    @DisplayName("Certificado del terminal: sin certificado o con el de otro cajero, 401 antes de la firma y sin llamar al core")
    void certificadoDelTerminal() throws Exception {
        verificar(mvc.perform(firmado(post("/cajero/v1/sesiones"), "POST", "/cajero/v1/sesiones", credenciales(), null, "ATM-001", ATM1)
                .with(solicitud -> {
                    solicitud.removeAttribute(FiltroCertificadoDeTerminal.ATRIBUTO_CERTIFICADOS);
                    return solicitud;
                })), 401, "CERTIFICADO_TERMINAL_REQUERIDO", "DEVOLVER_TARJETA");
        // ATM-002 firma bien como ATM-001 (conoce ese secreto) pero su certificado lo delata.
        verificar(mvc.perform(firmado(post("/cajero/v1/sesiones"), "POST", "/cajero/v1/sesiones", credenciales(), null, "ATM-001", ATM1)
                .with(x509(PkiDePrueba.certificado("ATM-002")))), 401, "CERTIFICADO_NO_CORRESPONDE", "DEVOLVER_TARJETA");
        core.verify();
    }

    @Test
    @DisplayName("Retiro aprobado y su repeticion: comprobante, cuenta enmascarada y orden de dispensar")
    void retiroYRepeticion() throws Exception {
        String token = abrirSesion();
        core.reset();
        core.expect(requestTo(CORE + "/cuentas/101/retiros")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", "clave-retiro-0001")).andExpect(header("X-Tarjeta-Id", "12"))
                .andExpect(header("X-Terminal-Id", "ATM-001")).andExpect(jsonPath("$.monto").value(3000))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(contrato("operacion-retiro.json")));
        MvcResult aprobado = retirar(token, "clave-retiro-0001", 3000).andExpect(status().isCreated()).andReturn();
        JsonNode comprobante = json.readTree(aprobado.getResponse().getContentAsString());
        assertThat(comprobante.get("comprobante").asText()).isEqualTo("OP-000123");
        assertThat(comprobante.get("cuenta").asText()).isEqualTo("****0101");
        assertThat(comprobante.get("accion").asText()).isEqualTo("DISPENSAR");
        assertThat(aprobado.getResponse().getHeader("Idempotency-Replayed")).isNull();

        core.reset();
        core.expect(requestTo(CORE + "/cuentas/101/retiros"))
                .andRespond(withSuccess(contrato("operacion-retiro.json"), MediaType.APPLICATION_JSON)
                        .header("Idempotency-Replayed", "true"));
        MvcResult repetido = retirar(token, "clave-retiro-0001", 3000).andExpect(status().isOk()).andReturn();
        assertThat(repetido.getResponse().getHeader("Idempotency-Replayed")).isEqualTo("true");
    }

    @Test
    @DisplayName("Sin clave, monto que no es multiplo de $1.000 o sobre el maximo: 400 sin llamar al core")
    void validacionesDelRetiro() throws Exception {
        String token = abrirSesion();
        core.reset();
        mvc.perform(firmado(post("/cajero/v1/retiros"), "POST", "/cajero/v1/retiros", "{\"monto\":3000}", null, "ATM-001", ATM1)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("CLAVE_IDEMPOTENCIA_REQUERIDA")));
        assertThat(cuerpo(retirar(token, "clave-monto-1500", 1500).andExpect(status().isBadRequest()))
                .get("mensajePantalla").asText()).isEqualTo("Ingrese un monto múltiplo de $1.000.");
        assertThat(cuerpo(retirar(token, "clave-monto-grande", 300000).andExpect(status().isBadRequest()))
                .get("mensajePantalla").asText()).isEqualTo("El monto máximo por retiro es $200.000.");
        core.verify();
    }

    @Test
    @DisplayName("Traduccion del retiro: 422, 409, 5xx incierto, timeout incierto, cuenta ocupada, core caido y credencial rechazada")
    void traduccionDelRetiro() throws Exception {
        String token = abrirSesion();
        core.reset();
        esperarRetiro(withStatus(HttpStatus.UNPROCESSABLE_ENTITY).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(contrato("error-saldo-insuficiente.json")));
        esperarRetiro(withStatus(HttpStatus.UNPROCESSABLE_ENTITY).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body("{\"codigo\":\"LIMITE_DIARIO_EXCEDIDO\"}"));
        esperarRetiro(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_PROBLEM_JSON).body("{\"codigo\":\"IDEMPOTENCIA_CONFLICTO\"}"));
        esperarRetiro(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_PROBLEM_JSON).body("{\"codigo\":\"ERROR_INTERNO\"}"));
        esperarRetiro(withException(new SocketTimeoutException("Read timed out")));
        esperarRetiro(withStatus(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_PROBLEM_JSON).body("{\"codigo\":\"CUENTA_OCUPADA\"}"));
        esperarRetiro(withException(new ConnectException("Connection refused")));
        esperarRetiro(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_PROBLEM_JSON).body("{\"codigo\":\"NO_AUTENTICADO\"}"));
        esperarRetiro(withException(new IOException("Connection reset")));

        verificar(retirar(token, "traduccion-0001", 6000), 422, "SALDO_INSUFICIENTE", "MOSTRAR_MENU");
        verificar(retirar(token, "traduccion-0002", 8000), 422, "LIMITE_DIARIO_EXCEDIDO", "MOSTRAR_MENU");
        verificar(retirar(token, "traduccion-0003", 1000), 409, "IDEMPOTENCIA_CONFLICTO", "NINGUNA");
        verificar(retirar(token, "traduccion-0004", 1000), 504, "RESULTADO_INCIERTO", "REINTENTAR_MISMA_CLAVE");
        verificar(retirar(token, "traduccion-0005", 1000), 504, "RESULTADO_INCIERTO", "REINTENTAR_MISMA_CLAVE");
        verificar(retirar(token, "traduccion-0006", 1000), 503, "CUENTA_OCUPADA", "REINTENTAR_MISMA_CLAVE");
        verificar(retirar(token, "traduccion-0007", 1000), 503, "SERVICIO_NO_DISPONIBLE", "DEVOLVER_TARJETA");
        verificar(retirar(token, "traduccion-0008", 1000), 503, "SERVICIO_NO_DISPONIBLE", "DEVOLVER_TARJETA");
        verificar(retirar(token, "traduccion-0009", 1000), 504, "RESULTADO_INCIERTO", "REINTENTAR_MISMA_CLAVE");
    }

    @Test
    @DisplayName("Health y el contrato OpenAPI no exigen firma; una ruta desconocida del cajero si")
    void rutasPublicas() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("BFF Cajero")));
        mvc.perform(get("/cajero/v1/inexistente")).andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------------------------------------

    private String abrirSesion() throws Exception {
        responderSesion(withSuccess(contrato("tarjeta-autenticada.json"), MediaType.APPLICATION_JSON));
        MvcResult resultado = sesion(credenciales()).andExpect(status().isCreated()).andReturn();
        JsonNode sesion = json.readTree(resultado.getResponse().getContentAsString());
        assertThat(sesion.get("titular").asText()).isEqualTo("Jane S.");
        assertThat(sesion.get("accion").asText()).isEqualTo("MOSTRAR_MENU");
        assertThat(sesion.get("expiraEn").asInt()).isEqualTo(120);
        return sesion.get("tokenSesion").asText();
    }

    private void responderSesion(org.springframework.test.web.client.ResponseCreator respuesta) {
        core.expect(requestTo(CORE + "/autenticacion/tarjetas")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.numeroTarjeta").value(PAN))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TokenDePrueba.TOKEN))
                .andRespond(respuesta);
    }

    private void esperarRetiro(org.springframework.test.web.client.ResponseCreator respuesta) {
        core.expect(requestTo(CORE + "/cuentas/101/retiros")).andRespond(respuesta);
    }

    private static String credenciales() {
        return "{\"numeroTarjeta\":\"" + PAN + "\",\"pin\":\"1234\"}";
    }

    private ResultActions sesion(String cuerpo) throws Exception {
        return mvc.perform(firmado(post("/cajero/v1/sesiones"), "POST", "/cajero/v1/sesiones", cuerpo, null, "ATM-001", ATM1));
    }

    private ResultActions consultarSaldo(String token, String terminal, String secreto) throws Exception {
        MockHttpServletRequestBuilder solicitud = firmado(get("/cajero/v1/saldo"), "GET", "/cajero/v1/saldo", "", null, terminal, secreto);
        if (token != null) {
            solicitud.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return mvc.perform(solicitud);
    }

    private ResultActions retirar(String token, String clave, int monto) throws Exception {
        cerrarCircuitos();
        String cuerpo = "{\"monto\":" + monto + "}";
        return mvc.perform(firmado(post("/cajero/v1/retiros"), "POST", "/cajero/v1/retiros", cuerpo, clave, "ATM-001", ATM1)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private void verificar(ResultActions resultado, int estado, String codigo, String accion) throws Exception {
        JsonNode cuerpo = cuerpo(resultado.andExpect(status().is(estado)));
        assertThat(cuerpo.get("codigo").asText()).isEqualTo(codigo);
        assertThat(cuerpo.get("accion").asText()).isEqualTo(accion);
        assertThat(cuerpo.get("mensajePantalla").asText()).isNotBlank();
    }

    private static MockHttpServletRequestBuilder firmado(MockHttpServletRequestBuilder solicitud, String metodo, String ruta,
                                                         String cuerpo, String clave, String terminal, String secreto) {
        String marca = String.valueOf(Instant.now().getEpochSecond());
        String nonce = "prueba-" + UUID.randomUUID();
        // El certificado del terminal que Tomcat deja en la solicitud tras el handshake mTLS.
        solicitud.with(x509(PkiDePrueba.certificado(terminal)));
        solicitud.header("X-Terminal-Id", terminal).header("X-Marca-Tiempo", marca).header("X-Nonce", nonce)
                .header("X-Firma", FirmaHmac.firmar(secreto, metodo, ruta, marca, nonce, clave, cuerpo.getBytes(StandardCharsets.UTF_8)));
        if (clave != null) {
            solicitud.header("Idempotency-Key", clave);
        }
        if (!cuerpo.isEmpty()) {
            solicitud.contentType(MediaType.APPLICATION_JSON).content(cuerpo);
        }
        return solicitud;
    }

    private String tokenManual(Instant emitido, Instant vence, String audiencia) {
        JwtClaimsSet reclamos = JwtClaimsSet.builder().issuer("bff-cajero").audience(List.of(audiencia)).subject("12")
                .issuedAt(emitido).expiresAt(vence).id(UUID.randomUUID().toString())
                .claim("cuenta", 101).claim("terminal", "ATM-001").build();
        return codificador.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), reclamos)).getTokenValue();
    }

    private JsonNode cuerpo(ResultActions resultado) throws Exception {
        return json.readTree(resultado.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static String contrato(String archivo) throws IOException {
        return Files.readString(Path.of("contratos", "core-api", archivo));
    }
}
