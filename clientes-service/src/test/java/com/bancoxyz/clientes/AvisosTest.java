package com.bancoxyz.clientes;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bancoxyz.clientes.aviso.ServicioDeAvisos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Avisos al cliente desde los eventos de Kafka. */
class AvisosTest extends PruebaDeClientes {

    @Autowired
    private ServicioDeAvisos avisos;

    @Test
    @DisplayName("Transaccion completada, rechazada y alerta: un aviso legible cada una; un evento repetido no duplica")
    void avisos() throws Exception {
        avisos.movimiento(json.readTree("{\"eventoId\":\"e1\",\"tipo\":\"TransaccionCompletada\",\"operacion\":\"TRANSFERENCIA\","
                + "\"clienteId\":4,\"cuentaOrigen\":101,\"cuentaDestino\":102,\"monto\":1500,\"saldoOrigen\":6540}"));
        avisos.movimiento(json.readTree("{\"eventoId\":\"e1\",\"tipo\":\"TransaccionCompletada\",\"operacion\":\"TRANSFERENCIA\","
                + "\"clienteId\":4,\"cuentaOrigen\":101,\"cuentaDestino\":102,\"monto\":1500,\"saldoOrigen\":6540}"));
        avisos.movimiento(json.readTree("{\"eventoId\":\"e2\",\"tipo\":\"TransaccionRechazada\",\"operacion\":\"PAGO_SERVICIO\","
                + "\"clienteId\":4,\"cuentaOrigen\":101,\"monto\":90000,\"motivo\":\"SALDO_INSUFICIENTE\"}"));
        avisos.alerta(json.readTree("{\"eventoId\":\"e3\",\"tipo\":\"TarjetaBloqueada\",\"clienteId\":4,"
                + "\"detalle\":\"Tarjeta bloqueada por 3 PIN incorrectos en cajero\"}"));

        mvc.perform(get("/api/v1/clientes/" + DIANA + "/avisos").with(cliente("diana.prince", 4, DIANA, "clientes.leer")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].titulo").value("Alerta: tarjeta bloqueada"))
                .andExpect(jsonPath("$[0].seguridad").value(true))
                .andExpect(jsonPath("$[1].mensaje").value("Pago de servicio por $90.000 desde la cuenta 101 rechazada: saldo insuficiente"))
                .andExpect(jsonPath("$[2].mensaje").value("Transferencia de $1.500 desde la cuenta 101 a la 102. Saldo disponible: $6.540"));
    }

    @Test
    @DisplayName("Los avisos de un cliente no los ve otro (404)")
    void avisosAjenos() throws Exception {
        mvc.perform(get("/api/v1/clientes/" + DIANA + "/avisos").with(cliente("steve.rogers", 7, STEVE, "clientes.leer")))
                .andExpect(status().isNotFound());
    }
}
