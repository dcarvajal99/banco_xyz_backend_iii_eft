package com.bancoxyz.bff.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.ConnectException;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.client.AutoConfigureMockRestServiceServer;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.client.MockRestServiceServer;

@ActiveProfiles("prueba")
@SpringBootTest(properties = "spring.jackson.deserialization.fail-on-missing-creator-properties=true")
@AutoConfigureMockMvc
@AutoConfigureMockRestServiceServer
class CuentasYEjecutivoTest extends PruebaWeb {

    @Autowired
    private MockRestServiceServer servidor;

    @Override
    protected MockRestServiceServer core() {
        return servidor;
    }

    @Test
    @DisplayName("Detalle de cuenta: completa, con estados anuales y la primera pagina con metadatos")
    void detalleDeCuenta() throws Exception {
        MockHttpSession sesion = iniciarSesion(cliente());
        servidor.expect(requestTo(CORE + "/cuentas/101")).andRespond(withSuccess(contrato("cuenta.json"), MediaType.APPLICATION_JSON));
        servidor.expect(requestTo(CORE + "/cuentas/101/estados-anuales")).andRespond(withSuccess(contrato("estados-anuales.json"), MediaType.APPLICATION_JSON));
        servidor.expect(requestTo(CORE + "/cuentas/101/movimientos?pagina=0&tamano=25")).andRespond(withSuccess(contrato("pagina-movimientos.json"), MediaType.APPLICATION_JSON));
        JsonNode detalle = cuerpo(mvc.perform(get("/web/api/cuentas/101").session(sesion)).andExpect(status().isOk()));
        assertThat(detalle.at("/cuenta/tasaMensualPorcentaje").asText()).isEqualTo("0,50 %");
        assertThat(detalle.at("/cuenta/interesCierre").decimalValue()).isEqualByComparingTo("60");
        assertThat(detalle.at("/movimientos/primera").asBoolean()).isTrue();
        assertThat(detalle.at("/movimientos/ultima").asBoolean()).isFalse();
        assertThat(detalle.at("/movimientos/contenido/1/tipoEtiqueta").asText()).isEqualTo("Depósito");
        assertThat(detalle.get("estadosAnuales")).hasSize(1);
    }

    @Test
    @DisplayName("Movimientos paginados: tamano fuera de rango 400; ultima pagina marcada")
    void paginacion() throws Exception {
        MockHttpSession sesion = iniciarSesion(cliente());
        mvc.perform(get("/web/api/cuentas/101/movimientos?tamano=101").session(sesion)).andExpect(status().isBadRequest());
        mvc.perform(get("/web/api/cuentas/101/movimientos?pagina=-1").session(sesion)).andExpect(status().isBadRequest());
        mvc.perform(get("/web/api/cuentas/abc/movimientos").session(sesion)).andExpect(status().isBadRequest());
        servidor.expect(requestTo(CORE + "/cuentas/101/movimientos?pagina=2&tamano=20"))
                .andRespond(withSuccess(contrato("pagina-movimientos.json").replace("\"pagina\": 0", "\"pagina\": 2"), MediaType.APPLICATION_JSON));
        JsonNode pagina = cuerpo(mvc.perform(get("/web/api/cuentas/101/movimientos?pagina=2&tamano=20").session(sesion))
                .andExpect(status().isOk()));
        assertThat(pagina.get("ultima").asBoolean()).isTrue();
        assertThat(pagina.get("primera").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("Cuenta ajena: el core decide y la web entrega 404; core caido: 503")
    void cuentaAjenaYCoreCaido() throws Exception {
        MockHttpSession sesion = iniciarSesion(cliente());
        servidor.expect(requestTo(CORE + "/cuentas/140/movimientos?pagina=0&tamano=25"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(contrato("error-cuenta-no-encontrada.json")));
        servidor.expect(requestTo(CORE + "/cuentas/101/movimientos?pagina=0&tamano=25")).andRespond(withException(new ConnectException("refused")));
        assertThat(cuerpo(mvc.perform(get("/web/api/cuentas/140/movimientos").session(sesion)).andExpect(status().isNotFound()))
                .get("codigo").asText()).isEqualTo("CUENTA_NO_ENCONTRADA");
        assertThat(cuerpo(mvc.perform(get("/web/api/cuentas/101/movimientos").session(sesion)).andExpect(status().isServiceUnavailable()))
                .get("codigo").asText()).isEqualTo("SERVICIO_NO_DISPONIBLE");
    }

    @Test
    @DisplayName("Resumen diario con serie para grafico, rango invertido 400, y calidad con aviso")
    void consolaDelEjecutivo() throws Exception {
        MockHttpSession sesion = iniciarSesion(ejecutivo());
        servidor.expect(requestTo(CORE + "/reportes/resumen-diario?desde=2024-06-01&hasta=2024-06-30"))
                .andRespond(withSuccess(contrato("resumen-diario.json"), MediaType.APPLICATION_JSON));
        servidor.expect(requestTo(CORE + "/reportes/calidad")).andRespond(withSuccess(contrato("calidad.json"), MediaType.APPLICATION_JSON));
        JsonNode resumen = cuerpo(mvc.perform(get("/web/api/ejecutivo/resumen-diario?desde=2024-06-01&hasta=2024-06-30").session(sesion))
                .andExpect(status().isOk()));
        assertThat(resumen.at("/serie/fechas/0").asText()).isEqualTo("2024-06-30");
        assertThat(resumen.at("/serie/debitos/0").decimalValue()).isEqualByComparingTo("2400");
        assertThat(resumen.get("filas")).hasSize(1);
        JsonNode calidad = cuerpo(mvc.perform(get("/web/api/ejecutivo/calidad").session(sesion)).andExpect(status().isOk()));
        assertThat(calidad.get("aviso").asText()).contains("degradada");
        assertThat(calidad.get("totalRechazados").asLong()).isEqualTo(664);
        mvc.perform(get("/web/api/ejecutivo/resumen-diario?desde=2024-07-01&hasta=2024-06-01").session(sesion))
                .andExpect(status().isBadRequest());
    }
}
