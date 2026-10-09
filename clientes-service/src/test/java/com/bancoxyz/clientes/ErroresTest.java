package com.bancoxyz.clientes;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** Retroalimentacion de la semana 8: TODO error sale con el mismo ProblemDetail (claves estables y un codigo). */
class ErroresTest extends PruebaDeClientes {

    private static void formaComun(ResultActions respuesta, int estado, String codigo) throws Exception {
        respuesta.andExpect(status().is(estado))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:bancoxyz:problema:" + codigo.toLowerCase().replace('_', '-')))
                .andExpect(jsonPath("$.title").isNotEmpty())
                .andExpect(jsonPath("$.status").value(estado))
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andExpect(jsonPath("$.instance").isNotEmpty())
                .andExpect(jsonPath("$.codigo").value(codigo))
                .andExpect(jsonPath("$.correlacionId").isNotEmpty());
    }

    @Test
    @DisplayName("400 (JSON ilegible, parametro de otro tipo), 401, 403, 404 y 405: misma forma")
    void mismaFormaParaTodos() throws Exception {
        formaComun(mvc.perform(put("/api/v1/clientes/4/perfil").with(cliente("diana.prince", 4, DIANA, "clientes.perfil"))
                .contentType(MediaType.APPLICATION_JSON).content("{no es json")), 400, "SOLICITUD_INVALIDA");
        formaComun(mvc.perform(get("/api/v1/clientes/abc").with(ejecutivo("clientes.leer"))), 400, "SOLICITUD_INVALIDA");
        formaComun(mvc.perform(get("/api/v1/clientes")), 401, "NO_AUTENTICADO");
        formaComun(mvc.perform(get("/api/v1/clientes").with(ejecutivo("cuentas.leer"))), 403, "ACCESO_DENEGADO");
        formaComun(mvc.perform(get("/api/v1/clientes/999").with(ejecutivo("clientes.leer"))), 404, "CLIENTE_NO_ENCONTRADO");
        formaComun(mvc.perform(put("/api/v1/clientes").with(ejecutivo("clientes.leer"))), 403, "ACCESO_DENEGADO");
    }

    @Test
    @DisplayName("El id de correlacion recibido se devuelve en el error (seguimiento de punta a punta)")
    void correlacion() throws Exception {
        mvc.perform(get("/api/v1/clientes/999").with(ejecutivo("clientes.leer")).header("X-Correlacion-Id", "prueba-123"))
                .andExpect(jsonPath("$.correlacionId").value("prueba-123"));
        mvc.perform(delete("/api/v1/clientes/4").with(ejecutivo("clientes.leer"))).andExpect(status().isForbidden());
    }
}
