package com.bancoxyz.bff.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.MockServerRestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.UnorderedRequestExpectationManager;

/** Panel agregado: las llamadas al core salen en paralelo y en orden no determinista, por eso el simulador no exige orden. */
@ActiveProfiles("prueba")
@SpringBootTest(properties = "spring.jackson.deserialization.fail-on-missing-creator-properties=true")
@AutoConfigureMockMvc
@Import(PanelWebTest.CoreSinOrden.class)
class PanelWebTest extends PruebaWeb {

    @TestConfiguration(proxyBeanMethods = false)
    static class CoreSinOrden {
        @Bean
        MockServerRestClientCustomizer coreSinOrden() {
            return new MockServerRestClientCustomizer(UnorderedRequestExpectationManager.class);
        }
    }

    @Autowired
    private MockServerRestClientCustomizer simulador;

    @Override
    protected MockRestServiceServer core() {
        return simulador.getServer();
    }

    @BeforeEach
    void limpiar() {
        core().reset();
    }

    @Test
    @DisplayName("Panel del cliente: 2N+2 llamadas en paralelo juntadas en una respuesta completa con etiquetas y totales")
    void panelDelCliente() throws Exception {
        MockHttpSession sesion = iniciarSesion(cliente());
        esperarPanelDeCliente(true);
        JsonNode panel = cuerpo(mvc.perform(get("/web/api/panel").session(sesion)).andExpect(status().isOk()));
        core().verify();

        assertThat(panel.get("titular").asText()).isEqualTo("Jane Smith");
        assertThat(panel.at("/resumen/totalAhorro").decimalValue()).isEqualByComparingTo("12060");
        assertThat(panel.at("/resumen/totalDeuda").decimalValue()).isEqualByComparingTo("8120");
        assertThat(panel.at("/resumen/cuentasConAnomalia").asInt()).isEqualTo(1);
        assertThat(panel.at("/cuentas/0/tipoEtiqueta").asText()).isEqualTo("Cuenta de ahorro");
        assertThat(panel.at("/cuentas/0/tasaMensualPorcentaje").asText()).isEqualTo("0,50 %");
        assertThat(panel.at("/cuentas/1/observacion").asText()).contains("Titular repetido");
        assertThat(panel.get("movimientosRecientes")).hasSize(4);
        assertThat(panel.at("/movimientosRecientes/0/fecha").asText()).isEqualTo("2026-09-12");
        assertThat(panel.get("estadosAnuales")).hasSize(2);
        assertThat(panel.at("/cierre/calidad").asText()).isEqualTo("DEGRADADA");
        assertThat(panel.at("/cierre/aviso").asText()).contains("calidad degradada");
        assertThat(panel.get("advertencias")).isEmpty();
    }

    @Test
    @DisplayName("Degradacion elegante: si fallan los estados anuales de una cuenta, el panel sale igual con una advertencia")
    void degradacionElegante() throws Exception {
        MockHttpSession sesion = iniciarSesion(cliente());
        esperarPanelDeCliente(false);
        JsonNode panel = cuerpo(mvc.perform(get("/web/api/panel").session(sesion)).andExpect(status().isOk()));
        assertThat(panel.get("estadosAnuales")).hasSize(1);
        assertThat(panel.get("advertencias")).hasSize(1);
        assertThat(panel.at("/advertencias/0").asText()).contains("117");
    }

    @Test
    @DisplayName("Si fallan las cuentas no hay panel: 502 ERROR_CORE")
    void cuentasEsenciales() throws Exception {
        MockHttpSession sesion = iniciarSesion(cliente());
        core().expect(requestTo(CORE + "/clientes/6/cuentas")).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        assertThat(cuerpo(mvc.perform(get("/web/api/panel").session(sesion)).andExpect(status().isBadGateway()))
                .get("codigo").asText()).isEqualTo("ERROR_CORE");
    }

    @Test
    @DisplayName("Panel del ejecutivo: clientes, calidad con alertas y resumen diario, armado por otra estrategia")
    void panelDelEjecutivo() throws Exception {
        MockHttpSession sesion = iniciarSesion(ejecutivo());
        core().expect(requestTo(CORE + "/clientes")).andRespond(withSuccess(contrato("clientes.json"), MediaType.APPLICATION_JSON));
        core().expect(requestTo(CORE + "/reportes/calidad")).andRespond(withSuccess(contrato("calidad.json"), MediaType.APPLICATION_JSON));
        core().expect(requestTo(CORE + "/reportes/resumen-diario")).andRespond(withSuccess(contrato("resumen-diario.json"), MediaType.APPLICATION_JSON));
        JsonNode panel = cuerpo(mvc.perform(get("/web/api/panel").session(sesion)).andExpect(status().isOk()));
        assertThat(panel.at("/clientes/0/nombre").asText()).isEqualTo("Jane Smith");
        assertThat(panel.at("/calidad/totalRechazados").asLong()).isEqualTo(664);
        assertThat(panel.at("/calidad/aviso").asText()).isNotBlank();
        assertThat(panel.get("alertas").toString()).contains("DEGRADADA").contains("664 filas OMITIDO");
        assertThat(panel.get("resumenDiarioUltimos")).hasSize(1);
        assertThat(panel.has("titular")).isFalse();
    }

    @Test
    @DisplayName("El ejecutivo ve el panel de un cliente; si el cliente no existe, 404")
    void panelDeClienteVistoPorElEjecutivo() throws Exception {
        MockHttpSession sesion = iniciarSesion(ejecutivo());
        core().expect(requestTo(CORE + "/clientes")).andRespond(withSuccess(contrato("clientes.json"), MediaType.APPLICATION_JSON));
        esperarPanelDeCliente(true);
        JsonNode panel = cuerpo(mvc.perform(get("/web/api/ejecutivo/clientes/6/panel").session(sesion)).andExpect(status().isOk()));
        assertThat(panel.get("titular").asText()).isEqualTo("Jane Smith");

        core().reset();
        core().expect(requestTo(CORE + "/clientes")).andRespond(withSuccess(contrato("clientes.json"), MediaType.APPLICATION_JSON));
        assertThat(cuerpo(mvc.perform(get("/web/api/ejecutivo/clientes/99/panel").session(sesion)).andExpect(status().isNotFound()))
                .get("codigo").asText()).isEqualTo("CLIENTE_NO_ENCONTRADO");
    }

    private void esperarPanelDeCliente(boolean estadosDe117Ok) throws Exception {
        core().expect(requestTo(CORE + "/clientes/6/cuentas")).andRespond(withSuccess(contrato("cuentas.json"), MediaType.APPLICATION_JSON));
        core().expect(requestTo(CORE + "/cuentas/101/movimientos?pagina=0&tamano=20"))
                .andRespond(withSuccess(contrato("pagina-movimientos.json"), MediaType.APPLICATION_JSON));
        core().expect(requestTo(CORE + "/cuentas/117/movimientos?pagina=0&tamano=20"))
                .andRespond(withSuccess(contrato("pagina-movimientos.json"), MediaType.APPLICATION_JSON));
        core().expect(requestTo(CORE + "/cuentas/101/estados-anuales"))
                .andRespond(withSuccess(contrato("estados-anuales.json"), MediaType.APPLICATION_JSON));
        core().expect(requestTo(CORE + "/cuentas/117/estados-anuales"))
                .andRespond(estadosDe117Ok ? withSuccess(contrato("estados-anuales.json"), MediaType.APPLICATION_JSON)
                        : withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        core().expect(requestTo(CORE + "/cierres/vigentes")).andRespond(withSuccess(contrato("cierres-vigentes.json"), MediaType.APPLICATION_JSON));
    }
}
