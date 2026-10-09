package com.bancoxyz.cuentas;

import static org.assertj.core.api.Assertions.assertThat;

import com.bancoxyz.cuentas.cierre.ResumenSincronizacion;
import com.bancoxyz.cuentas.cierre.SincronizadorDeCierre;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Apertura de las cuentas del cierre publicado por el batch (migracion.cierre_cuenta). */
class SincronizacionTest extends PruebaDeCuentas {

    @Test
    @DisplayName("Se publica la ultima ejecucion COMPLETED (la 10), nunca la FAILED aunque sea mas nueva")
    void soloElCierreCompletado() {
        assertThat(jdbc.sql("select id from cuentas.cuenta order by id").query(Long.class).list())
                .containsExactly(101L, 102L, 103L, 104L, 105L);
        assertThat(saldo(101)).isEqualTo("8040");
        assertThat(jdbc.sql("select cliente_id from cuentas.cuenta where id = 105").query(Long.class).single()).isEqualTo(DIANA);
        assertThat(jdbc.sql("select count(*) from cuentas.cuenta where id = 199").query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("Tarjeta de demostracion solo para cuentas de ahorro con titular identificado")
    void tarjetasDeDemostracion() {
        assertThat(jdbc.sql("select cuenta_id from cuentas.tarjeta order by cuenta_id").query(Long.class).list())
                .containsExactly(101L, 102L, 105L);
        assertThat(SincronizadorDeCierre.numeroDeTarjeta(101)).isEqualTo("4000000000001018");
    }

    @Test
    @DisplayName("Volver a sincronizar no pisa un saldo que ya cambio en linea")
    void idempotente() {
        jdbc.sql("update cuentas.cuenta set saldo_disponible = 1 where id = 101").update();

        ResumenSincronizacion segunda = sincronizador.sincronizar();

        assertThat(segunda.cuentasNuevas()).isZero();
        assertThat(segunda.calidad()).isEqualTo("DEGRADADA");
        assertThat(saldo(101)).isEqualTo("1");
    }
}
