package com.bancoxyz.cuentas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bancoxyz.cuentas.clientes.ClienteDeClientes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Apertura, mantenimiento y cierre de cuentas por un ejecutivo. */
class AdministracionTest extends PruebaDeCuentas {

    private String abrir(String json) throws Exception {
        return mvc.perform(post("/api/v1/cuentas").with(ejecutivo("cuentas.administrar"))
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("Apertura: 201 con Location, id del rango 200001+, saldo cero y el titular confirmado en clientes-service")
    void apertura() throws Exception {
        when(clientes.titular(JANE)).thenReturn(new ClienteDeClientes.Titular("Jane Smith", true));

        mvc.perform(post("/api/v1/cuentas").with(ejecutivo("cuentas.administrar")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clienteId\":5,\"tipo\":\"ahorro\",\"alias\":\"Ahorro vacaciones\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.cuentaId").value(greaterThanOrEqualTo(200001)))
                .andExpect(jsonPath("$.titular").value("Jane Smith"))
                .andExpect(jsonPath("$.saldoDisponible").value(0.0))
                .andExpect(jsonPath("$.tasaMensual").value(0.005))
                .andExpect(jsonPath("$.origen").value("APERTURA"));
    }

    @Test
    @DisplayName("Apertura por un cliente: 403; tipo de cuenta invalido: 400 con el campo")
    void aperturaRechazada() throws Exception {
        mvc.perform(post("/api/v1/cuentas").with(cliente("jane.smith", JANE, "bff-web", "cuentas.administrar"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"clienteId\":5,\"tipo\":\"ahorro\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/cuentas").with(ejecutivo("cuentas.administrar")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clienteId\":5,\"tipo\":\"corriente\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errores[0].campo").value("tipo"));
    }

    @Test
    @DisplayName("Mantenimiento: alias y bloqueo; una cuenta bloqueada no admite retiros")
    void mantenimiento() throws Exception {
        mvc.perform(patch("/api/v1/cuentas/101").with(ejecutivo("cuentas.administrar")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alias\":\"Principal\",\"estado\":\"BLOQUEADA\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.alias").value("Principal"))
                .andExpect(jsonPath("$.estado").value("BLOQUEADA"));
        mvc.perform(post("/api/v1/cuentas/101/retiros").with(servicio("bff-cajero", "cajero.operar"))
                        .header("X-Tarjeta-Id", tarjetaDeCuenta(101)).header("X-Terminal-Id", "ATM-001")
                        .header("Idempotency-Key", "bloqueada-0001").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"monto\":1000}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.codigo").value("CUENTA_NO_OPERATIVA"));
    }

    @Test
    @DisplayName("Cierre: con saldo 422 CUENTA_CON_SALDO; en cero queda CERRADA, sus tarjetas bloqueadas y ya no cambia")
    void cierre() throws Exception {
        mvc.perform(post("/api/v1/cuentas/101/cierre").with(ejecutivo("cuentas.administrar"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"Solicitud del cliente\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.codigo").value("CUENTA_CON_SALDO"));

        jdbc.sql("update cuentas.cuenta set saldo_disponible = 0 where id = 101").update();
        mvc.perform(post("/api/v1/cuentas/101/cierre").with(ejecutivo("cuentas.administrar"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"Solicitud del cliente\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("CERRADA"))
                .andExpect(jsonPath("$.cerradaEn").isNotEmpty());
        assertThat(jdbc.sql("select bloqueada from cuentas.tarjeta where cuenta_id = 101").query(Boolean.class).single()).isTrue();
        mvc.perform(patch("/api/v1/cuentas/101").with(ejecutivo("cuentas.administrar")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"estado\":\"ACTIVA\"}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("CUENTA_CERRADA"));
    }
}
