package com.bancoxyz.bff.cajero;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Descubrimiento del core con la configuracion que entrega el Config Server: el BFF solo conoce el NOMBRE del servicio
 * ({@code https://banco-core-api/api/v1}) y Spring Cloud LoadBalancer lo resuelve en el catalogo. Aqui el catalogo es el
 * DiscoveryClient simple de Spring Cloud (el mismo contrato que Eureka) y el core es un simulador HTTPS que exige
 * certificado de cliente: se prueba que el balanceador y el mTLS funcionan juntos.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0", "banco.core.descubrimiento=true", "banco.core.url=https://banco-core-api/api/v1"})
@ActiveProfiles({"prueba", "tls"})
class DescubrimientoDelCoreTest {

    private static CoreSimuladoTls core;

    @LocalServerPort
    private int puerto;

    @Value("${banco.core.url}")
    private String urlDelCore;

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registro) {
        core = CoreSimuladoTls.iniciar(Map.of("/api/v1/autenticacion/tarjetas", "tarjeta-autenticada.json"));
        registro.add("banco.certificados", () -> PkiDePrueba.raiz().toString());
        registro.add("spring.cloud.discovery.client.simple.instances.banco-core-api[0].uri",
                () -> "https://localhost:" + URI.create(core.url()).getPort());
    }

    @AfterAll
    static void detenerCore() {
        core.detener();
    }

    @Test
    @DisplayName("Sin maquina ni puerto del core: el BFF lo encuentra por nombre y entra con su certificado (mTLS)")
    void coreEncontradoPorNombre() throws Exception {
        assertThat(URI.create(urlDelCore).getHost()).isEqualTo("banco-core-api");
        assertThat(URI.create(urlDelCore).getPort()).isEqualTo(-1);

        HttpResponse<String> respuesta = HttpClient.newBuilder().sslContext(PkiDePrueba.contexto("ATM-001")).build()
                .send(sesion(), HttpResponse.BodyHandlers.ofString());

        assertThat(respuesta.statusCode()).isEqualTo(201);
        assertThat(core.certificadosVistos()).isNotEmpty().allSatisfy(sujeto -> assertThat(sujeto).startsWith("CN=bff-cajero"));
    }

    private HttpRequest sesion() {
        String cuerpo = "{\"numeroTarjeta\":\"4000000000001018\",\"pin\":\"1234\"}";
        String marca = String.valueOf(Instant.now().getEpochSecond());
        String nonce = "descubrimiento-" + UUID.randomUUID();
        return HttpRequest.newBuilder(URI.create("https://localhost:" + puerto + "/cajero/v1/sesiones"))
                .header("Content-Type", "application/json")
                .header("X-Terminal-Id", "ATM-001").header("X-Marca-Tiempo", marca).header("X-Nonce", nonce)
                .header("X-Firma", FirmaHmac.firmar(TlsDelBffCajeroTest.ATM1, "POST",
                        "/cajero/v1/sesiones", marca, nonce, null, cuerpo.getBytes(StandardCharsets.UTF_8)))
                .POST(HttpRequest.BodyPublishers.ofString(cuerpo)).build();
    }
}
