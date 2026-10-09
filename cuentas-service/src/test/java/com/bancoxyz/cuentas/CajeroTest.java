package com.bancoxyz.cuentas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** Canal cajero: bff-cajero con su token de servicio (cajero.operar) y su certificado. */
class CajeroTest extends PruebaDeCuentas {

    private ResultActions pin(String pin) throws Exception {
        return mvc.perform(post("/api/v1/autenticacion/tarjetas").with(servicio("bff-cajero", "cajero.operar"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"numeroTarjeta\":\"4000000000001018\",\"pin\":\"" + pin + "\"}"));
    }

    private ResultActions retirar(long tarjeta, String clave, int monto) throws Exception {
        return mvc.perform(post("/api/v1/cuentas/101/retiros").with(servicio("bff-cajero", "cajero.operar"))
                .header("X-Tarjeta-Id", tarjeta).header("X-Terminal-Id", "ATM-001").header("Idempotency-Key", clave)
                .contentType(MediaType.APPLICATION_JSON).content("{\"monto\":" + monto + "}"));
    }

    @Test
    @DisplayName("Tarjeta y PIN correctos: el cajero recibe la cuenta y el titular; el saldo solo de esa cuenta")
    void tarjetaYSaldo() throws Exception {
        pin("1234").andExpect(status().isOk()).andExpect(jsonPath("$.cuentaId").value(101))
                .andExpect(jsonPath("$.titular").value("Diana Prince"));
        long tarjeta = tarjetaDeCuenta(101);
        mvc.perform(get("/api/v1/cuentas/101").with(servicio("bff-cajero", "cajero.operar"))
                .header("X-Tarjeta-Id", tarjeta).header("X-Terminal-Id", "ATM-001")).andExpect(status().isOk())
                .andExpect(jsonPath("$.saldoDisponible").value(8040.0));
        mvc.perform(get("/api/v1/cuentas/105").with(servicio("bff-cajero", "cajero.operar"))
                .header("X-Tarjeta-Id", tarjeta).header("X-Terminal-Id", "ATM-001")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Tercer PIN incorrecto: tarjeta bloqueada y alerta TarjetaBloqueada en el outbox")
    void bloqueoConAlerta() throws Exception {
        pin("0000").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.intentosRestantes").value(2));
        pin("0000").andExpect(status().isUnauthorized());
        pin("0000").andExpect(status().isLocked()).andExpect(jsonPath("$.codigo").value("TARJETA_BLOQUEADA"));
        pin("1234").andExpect(status().isLocked());
        assertThat(jdbc.sql("select topico || ':' || tipo || ':' || clave from cuentas.outbox").query(String.class).list())
                .containsExactly("seguridad.alertas:TarjetaBloqueada:4");
    }

    @Test
    @DisplayName("Retiro: 201, descuenta, publica TransaccionCompletada; repetir la misma clave no descuenta dos veces")
    void retiroIdempotente() throws Exception {
        long tarjeta = tarjetaDeCuenta(101);
        retirar(tarjeta, "retiro-00000001", 2000).andExpect(status().isCreated())
                .andExpect(jsonPath("$.saldoResultante").value(6040.0));
        retirar(tarjeta, "retiro-00000001", 2000).andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"));
        assertThat(saldo(101)).isEqualTo("6040");
        assertThat(jdbc.sql("select topico || ':' || tipo from cuentas.outbox").query(String.class).list())
                .containsExactly("cuentas.movimientos:TransaccionCompletada");
    }

    @Test
    @DisplayName("El cajero no puede usar consultas de la banca: un token sin cuentas.leer no lista cuentas")
    void cajeroNoListaCuentas() throws Exception {
        mvc.perform(get("/api/v1/clientes/4/cuentas").with(servicio("bff-cajero", "cajero.operar")))
                .andExpect(status().isForbidden());
    }
}
