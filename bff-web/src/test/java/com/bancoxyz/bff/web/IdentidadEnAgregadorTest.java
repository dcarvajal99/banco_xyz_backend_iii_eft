package com.bancoxyz.bff.web;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.MockServerRestClientCustomizer;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.client.MockRestServiceServer;

/**
 * Con UN solo hilo en el agregador, el mismo hilo atiende el panel de dos usuarios seguidos. Si la identidad viajara
 * pegada al hilo, la segunda persona veria las cuentas de la primera: cada llamada al core se verifica con el
 * X-Usuario-Id y el X-Correlacion-Id de SU solicitud.
 */
@ActiveProfiles("prueba")
@SpringBootTest(properties = {"banco.web.agregador.hilos=1",
        "spring.jackson.deserialization.fail-on-missing-creator-properties=true"})
@AutoConfigureMockMvc
@Import(PanelWebTest.CoreSinOrden.class)
class IdentidadEnAgregadorTest extends PruebaWeb {

    @Autowired
    private MockServerRestClientCustomizer simulador;

    @Override
    protected MockRestServiceServer core() {
        return simulador.getServer();
    }

    @Test
    @DisplayName("Dos paneles seguidos de usuarios distintos sobre un pool de 1 hilo: ninguna llamada lleva la identidad del otro")
    void sinFugaDeIdentidadEntreSolicitudes() throws Exception {
        core().reset();
        MockHttpSession jane = iniciarSesion(cliente());
        esperarPanel("6", "3", "corr-jane");
        mvc.perform(get("/web/api/panel").session(jane).header("X-Correlacion-Id", "corr-jane")).andExpect(status().isOk());
        core().verify();
        core().reset();

        MockHttpSession diana = iniciarSesion(cliente().replace("\"usuarioId\": 3", "\"usuarioId\": 9")
                .replace("\"clienteId\": 6", "\"clienteId\": 8").replace("Jane Smith", "Diana Prince"));
        esperarPanel("8", "9", "corr-diana");
        mvc.perform(get("/web/api/panel").session(diana).header("X-Correlacion-Id", "corr-diana")).andExpect(status().isOk());
        core().verify();
    }

    private void esperarPanel(String clienteId, String usuarioId, String correlacion) throws Exception {
        core().expect(requestTo(CORE + "/clientes/" + clienteId + "/cuentas")).andExpect(header("X-Usuario-Id", usuarioId))
                .andExpect(header("X-Correlacion-Id", correlacion))
                .andRespond(withSuccess(contrato("cuentas.json"), MediaType.APPLICATION_JSON));
        for (String cuenta : new String[]{"101", "117"}) {
            core().expect(requestTo(CORE + "/cuentas/" + cuenta + "/movimientos?pagina=0&tamano=20"))
                    .andExpect(header("X-Usuario-Id", usuarioId)).andExpect(header("X-Correlacion-Id", correlacion))
                    .andRespond(withSuccess(contrato("pagina-movimientos.json"), MediaType.APPLICATION_JSON));
            core().expect(requestTo(CORE + "/cuentas/" + cuenta + "/estados-anuales"))
                    .andExpect(header("X-Usuario-Id", usuarioId)).andExpect(header("X-Correlacion-Id", correlacion))
                    .andRespond(withSuccess(contrato("estados-anuales.json"), MediaType.APPLICATION_JSON));
        }
        core().expect(requestTo(CORE + "/cierres/vigentes")).andExpect(header("X-Correlacion-Id", correlacion))
                .andRespond(withSuccess(contrato("cierres-vigentes.json"), MediaType.APPLICATION_JSON));
    }
}
