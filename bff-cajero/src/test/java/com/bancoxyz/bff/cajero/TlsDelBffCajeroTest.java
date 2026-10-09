package com.bancoxyz.bff.cajero;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.bancoxyz.bff.cajero.seguridad.FirmaHmac;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * El BFF del cajero con el perfil tls real: el terminal abre mTLS con su certificado y ademas firma; el BFF abre mTLS
 * hacia un core simulado que exige certificado.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@ActiveProfiles({"prueba", "tls"})
class TlsDelBffCajeroTest {

    static final String ATM1 = "atm-001-secreto-de-desarrollo-0123456789";
    private static CoreSimuladoTls core;

    @LocalServerPort
    private int puerto;

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registro) {
        core = CoreSimuladoTls.iniciar(Map.of("/api/v1/autenticacion/tarjetas", "tarjeta-autenticada.json"));
        registro.add("banco.certificados", () -> PkiDePrueba.raiz().toString());
        registro.add("banco.core.url", core::url);
    }

    @AfterAll
    static void detenerCore() {
        core.detener();
    }

    @Test
    @DisplayName("Terminal con su certificado y firma: sesion 201 por TLS 1.3 y HTTP/2, sin gzip, HSTS; el BFF entra al core con el suyo")
    void terminalAutenticado() throws Exception {
        HttpResponse<String> sesion = terminal("ATM-001").send(sesion("ATM-001"), HttpResponse.BodyHandlers.ofString());

        assertThat(sesion.statusCode()).isEqualTo(201);
        assertThat(sesion.body()).contains("\"tokenSesion\"");
        assertThat(sesion.version()).isEqualTo(HttpClient.Version.HTTP_2);
        assertThat(sesion.sslSession().orElseThrow().getProtocol()).isEqualTo("TLSv1.3");
        assertThat(sesion.headers().firstValue("content-encoding")).isEmpty();
        assertThat(sesion.headers().firstValue("strict-transport-security").orElseThrow()).contains("max-age=31536000");
        assertThat(core.certificadosVistos()).isNotEmpty().allSatisfy(sujeto -> assertThat(sujeto).startsWith("CN=bff-cajero"));
    }

    @Test
    @DisplayName("Firma correcta sin certificado: 401 CERTIFICADO_TERMINAL_REQUERIDO")
    void sinCertificado() throws Exception {
        HttpResponse<String> respuesta = terminal(null).send(sesion("ATM-001"), HttpResponse.BodyHandlers.ofString());
        assertThat(respuesta.statusCode()).isEqualTo(401);
        assertThat(respuesta.body()).contains("\"codigo\":\"CERTIFICADO_TERMINAL_REQUERIDO\"");
    }

    @Test
    @DisplayName("Certificado de ATM-002 firmando como ATM-001: 401 CERTIFICADO_NO_CORRESPONDE")
    void certificadoDeOtroTerminal() throws Exception {
        HttpResponse<String> respuesta = terminal("ATM-002").send(sesion("ATM-001"), HttpResponse.BodyHandlers.ofString());
        assertThat(respuesta.statusCode()).isEqualTo(401);
        assertThat(respuesta.body()).contains("\"codigo\":\"CERTIFICADO_NO_CORRESPONDE\"");
    }

    @Test
    @DisplayName("Certificado ATM-001 emitido por una CA ajena: la conexion TLS no se establece")
    void certificadoNoConfiable() {
        assertThatThrownBy(() -> terminal("ATM-001-intruso").send(sesion("ATM-001"), HttpResponse.BodyHandlers.ofString()))
                .isInstanceOf(IOException.class);
    }

    private HttpClient terminal(String certificado) {
        return HttpClient.newBuilder().sslContext(PkiDePrueba.contexto(certificado)).build();
    }

    private HttpRequest sesion(String terminal) {
        String cuerpo = "{\"numeroTarjeta\":\"4000000000001018\",\"pin\":\"1234\"}";
        String marca = String.valueOf(Instant.now().getEpochSecond());
        String nonce = "tls-" + UUID.randomUUID();
        return HttpRequest.newBuilder(URI.create("https://localhost:" + puerto + "/cajero/v1/sesiones"))
                .header("Content-Type", "application/json")
                .header("X-Terminal-Id", terminal).header("X-Marca-Tiempo", marca).header("X-Nonce", nonce)
                .header("X-Firma", FirmaHmac.firmar(ATM1, "POST", "/cajero/v1/sesiones", marca, nonce, null,
                        cuerpo.getBytes(StandardCharsets.UTF_8)))
                .POST(HttpRequest.BodyPublishers.ofString(cuerpo)).build();
    }
}
