package com.bancoxyz.bff.movil;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * El BFF movil con el perfil tls real y un core simulado que exige certificado: la portada viaja por HTTP/2 sobre
 * TLS 1.3, comprimida, con ETag y revalidacion (304) tal como la veria la app.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@ActiveProfiles({"prueba", "tls"})
class TlsDelBffMovilTest {

    private static final String DISPOSITIVO = "iphone-de-jane-01";
    private static CoreSimuladoTls core;

    @LocalServerPort
    private int puerto;

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registro) {
        core = CoreSimuladoTls.iniciar(Map.of(
                "/api/v1/autenticacion/usuarios", "usuario-autenticado.json",
                "/api/v1/clientes/6/cuentas", "cuentas.json",
                "/api/v1/cuentas/\\d+/movimientos", "pagina-movimientos.json"));
        registro.add("banco.certificados", () -> PkiDePrueba.raiz().toString());
        registro.add("banco.core.url", core::url);
    }

    @AfterAll
    static void detenerCore() {
        core.detener();
    }

    @Test
    @DisplayName("Sesion y portada por HTTPS: tokens sin cache, portada gzip con ETag y private/no-cache, 304 sin cuerpo; mTLS al core")
    void portadaPorHttps() throws Exception {
        HttpClient app = HttpClient.newBuilder().sslContext(PkiDePrueba.contexto(null)).build();
        HttpResponse<String> sesion = app.send(HttpRequest.newBuilder(URI.create(base() + "/movil/v1/sesiones"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"usuario\":\"jane.smith\",\"password\":\"Cliente2026!\",\"dispositivoId\":\""
                        + DISPOSITIVO + "\"}")).build(), HttpResponse.BodyHandlers.ofString());

        assertThat(sesion.statusCode()).isEqualTo(201);
        assertThat(sesion.version()).isEqualTo(HttpClient.Version.HTTP_2);
        assertThat(sesion.sslSession().orElseThrow().getProtocol()).isEqualTo("TLSv1.3");
        assertThat(sesion.headers().firstValue("cache-control").orElseThrow()).contains("no-store");
        assertThat(sesion.headers().firstValue("strict-transport-security").orElseThrow()).contains("max-age=31536000");
        assertThat(sesion.headers().firstValue("content-security-policy")).hasValue("default-src 'none'; frame-ancestors 'none'");
        String access = new ObjectMapper().readTree(sesion.body()).get("accessToken").asText();

        HttpResponse<byte[]> portada = app.send(portada(access, null), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(portada.statusCode()).isEqualTo(200);
        assertThat(portada.headers().firstValue("content-encoding")).hasValue("gzip");
        assertThat(portada.headers().firstValue("cache-control")).hasValue("no-cache, private");
        String etag = portada.headers().firstValue("etag").orElseThrow();
        assertThat(etag).startsWith("W/\"");
        assertThat(descomprimir(portada.body())).contains("\"nombre\":\"Jane\"");

        HttpResponse<byte[]> revalidada = app.send(portada(access, etag), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(revalidada.statusCode()).isEqualTo(304);
        assertThat(revalidada.body()).isEmpty();
        assertThat(core.certificadosVistos()).isNotEmpty().allSatisfy(sujeto -> assertThat(sujeto).startsWith("CN=bff-movil"));
    }

    private HttpRequest portada(String access, String etag) {
        HttpRequest.Builder solicitud = HttpRequest.newBuilder(URI.create(base() + "/movil/v1/inicio"))
                .header("Authorization", "Bearer " + access).header("X-Dispositivo-Id", DISPOSITIVO)
                .header("Accept-Encoding", "gzip");
        if (etag != null) {
            solicitud.header("If-None-Match", etag);
        }
        return solicitud.build();
    }

    private String base() {
        return "https://localhost:" + puerto;
    }

    private static String descomprimir(byte[] gzip) throws IOException {
        try (GZIPInputStream entrada = new GZIPInputStream(new ByteArrayInputStream(gzip))) {
            return new String(entrada.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
