package com.bancoxyz.bff.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * El BFF web levantado con el perfil tls real (application-tls.properties) y un core simulado que exige certificado:
 * HTTPS hacia el navegador, mTLS hacia el core, cookies seguras, encabezados y gzip medidos en el socket.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@ActiveProfiles({"prueba", "tls"})
class TlsDelBffWebTest {

    private static CoreSimuladoTls core;

    @LocalServerPort
    private int puerto;

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registro) {
        core = CoreSimuladoTls.iniciar(Map.of(
                "/api/v1/autenticacion/usuarios", "usuario-autenticado.json",
                "/api/v1/clientes/6/cuentas", "cuentas.json",
                "/api/v1/cuentas/\\d+/movimientos", "pagina-movimientos.json",
                "/api/v1/cuentas/\\d+/estados-anuales", "estados-anuales.json",
                "/api/v1/cierres/vigentes", "cierres-vigentes.json"));
        registro.add("banco.certificados", () -> PkiDePrueba.raiz().toString());
        registro.add("banco.core.url", core::url);
    }

    @AfterAll
    static void detenerCore() {
        core.detener();
    }

    @Test
    @DisplayName("Login por HTTPS: TLS 1.3, HTTP/2, cookies Secure/HttpOnly/SameSite, HSTS y CSP; el BFF entra al core con su certificado")
    void loginPorHttps() throws Exception {
        HttpClient navegador = navegador();
        HttpResponse<String> login = navegador.send(HttpRequest.newBuilder(URI.create(base() + "/web/api/sesion"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"usuario\":\"jane.smith\",\"password\":\"Cliente2026!\"}")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(login.version()).isEqualTo(HttpClient.Version.HTTP_2);
        assertThat(login.sslSession().orElseThrow().getProtocol()).isEqualTo("TLSv1.3");
        assertThat(cookie(login, "BFFWEB_SESION")).contains("Secure", "HttpOnly", "SameSite=Strict");
        assertThat(login.headers().firstValue("strict-transport-security").orElseThrow()).contains("max-age=31536000", "includeSubDomains");
        assertThat(login.headers().firstValue("content-security-policy")).hasValue("default-src 'none'; frame-ancestors 'none'");
        assertThat(login.headers().firstValue("referrer-policy")).hasValue("no-referrer");
        assertThat(core.certificadosVistos()).isNotEmpty().allSatisfy(sujeto -> assertThat(sujeto).startsWith("CN=bff-web"));

        HttpResponse<String> csrf = navegador.send(HttpRequest.newBuilder(URI.create(base() + "/web/api/csrf")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(cookie(csrf, "XSRF-TOKEN")).contains("Secure", "SameSite=Strict").doesNotContain("HttpOnly");
    }

    @Test
    @DisplayName("Panel agregado sobre HTTPS: JSON comprimido con gzip y sin guardar en la cache del navegador")
    void panelComprimido() throws Exception {
        HttpClient navegador = navegador();
        navegador.send(HttpRequest.newBuilder(URI.create(base() + "/web/api/sesion")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"usuario\":\"jane.smith\",\"password\":\"Cliente2026!\"}")).build(),
                HttpResponse.BodyHandlers.discarding());

        HttpResponse<byte[]> panel = navegador.send(HttpRequest.newBuilder(URI.create(base() + "/web/api/panel"))
                .header("Accept-Encoding", "gzip").build(), HttpResponse.BodyHandlers.ofByteArray());

        assertThat(panel.statusCode()).isEqualTo(200);
        assertThat(panel.headers().firstValue("content-encoding")).hasValue("gzip");
        assertThat(panel.headers().firstValue("cache-control").orElseThrow()).contains("no-store");
        String json = descomprimir(panel.body());
        assertThat(json).contains("\"titular\":\"Jane Smith\"");
        assertThat(panel.body().length).isLessThan(json.getBytes(StandardCharsets.UTF_8).length / 2);
    }

    private HttpClient navegador() {
        return HttpClient.newBuilder().sslContext(PkiDePrueba.contexto(null)).cookieHandler(new CookieManager()).build();
    }

    private String base() {
        return "https://localhost:" + puerto;
    }

    private static String cookie(HttpResponse<?> respuesta, String nombre) {
        return respuesta.headers().allValues("set-cookie").stream().filter(c -> c.startsWith(nombre + "="))
                .findFirst().orElseThrow();
    }

    private static String descomprimir(byte[] gzip) throws IOException {
        try (GZIPInputStream entrada = new GZIPInputStream(new ByteArrayInputStream(gzip))) {
            return new String(entrada.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
