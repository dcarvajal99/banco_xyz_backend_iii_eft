package com.bancoxyz.bff.cajero;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.x509;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import com.bancoxyz.bff.cajero.core.TarjetaAutenticadaCore;
import com.bancoxyz.bff.cajero.seguridad.EmisorDeSesiones;
import com.bancoxyz.bff.cajero.seguridad.FirmaHmac;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Descubrimiento activo y ninguna instancia del core en el catalogo (el core cayo y Eureka ya lo dio de baja): el
 * balanceador no tiene a quien enviar la solicitud. Para el cajero eso es "no salio": 503 y devolver la tarjeta, nunca
 * 504 con reintento, que corresponde a un retiro enviado sin respuesta.
 */
@ActiveProfiles("prueba")
@SpringBootTest(properties = {"banco.core.descubrimiento=true", "banco.core.url=https://cuentas-service/api/v1"})
@AutoConfigureMockMvc
class CoreSinInstanciasTest {

    static final String ATM1 = "atm-001-secreto-de-desarrollo-0123456789";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private EmisorDeSesiones emisor;

    @Autowired
    private ObjectMapper json;

    @Test
    @DisplayName("Retiro sin instancias del core en Eureka: 503 SERVICIO_NO_DISPONIBLE y DEVOLVER_TARJETA, no 504")
    void retiroSinInstancias() throws Exception {
        TarjetaAutenticadaCore tarjeta = json.readValue(contrato("tarjeta-autenticada.json"), TarjetaAutenticadaCore.class);
        String token = emisor.emitir(tarjeta, "ATM-001").token();
        String cuerpo = "{\"monto\":1000}";

        ResultActions retiro = mvc.perform(firmado(post("/cajero/v1/retiros"), "/cajero/v1/retiros", cuerpo, "sin-instancias-0001")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));

        verificar(retiro, 503, "SERVICIO_NO_DISPONIBLE", "DEVOLVER_TARJETA");
    }

    @Test
    @DisplayName("Sesion sin instancias del core en Eureka: 503 y DEVOLVER_TARJETA")
    void sesionSinInstancias() throws Exception {
        ResultActions sesion = mvc.perform(firmado(post("/cajero/v1/sesiones"), "/cajero/v1/sesiones",
                "{\"numeroTarjeta\":\"4000000000001018\",\"pin\":\"1234\"}", null));

        verificar(sesion, 503, "SERVICIO_NO_DISPONIBLE", "DEVOLVER_TARJETA");
    }

    private void verificar(ResultActions resultado, int estado, String codigo, String accion) throws Exception {
        JsonNode cuerpo = json.readTree(resultado.andExpect(status().is(estado)).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
        assertThat(cuerpo.get("codigo").asText()).isEqualTo(codigo);
        assertThat(cuerpo.get("accion").asText()).isEqualTo(accion);
    }

    private static MockHttpServletRequestBuilder firmado(MockHttpServletRequestBuilder solicitud, String ruta, String cuerpo,
                                                         String clave) {
        String marca = String.valueOf(Instant.now().getEpochSecond());
        String nonce = "sin-instancias-" + UUID.randomUUID();
        solicitud.with(x509(PkiDePrueba.certificado("ATM-001")));
        solicitud.header("X-Terminal-Id", "ATM-001").header("X-Marca-Tiempo", marca).header("X-Nonce", nonce)
                .header("X-Firma", FirmaHmac.firmar(ATM1, "POST", ruta, marca, nonce, clave, cuerpo.getBytes(StandardCharsets.UTF_8)));
        if (clave != null) {
            solicitud.header("Idempotency-Key", clave);
        }
        return solicitud.contentType(MediaType.APPLICATION_JSON).content(cuerpo);
    }

    private static String contrato(String archivo) {
        try {
            return Files.readString(Path.of("contratos", "core-api", archivo));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
