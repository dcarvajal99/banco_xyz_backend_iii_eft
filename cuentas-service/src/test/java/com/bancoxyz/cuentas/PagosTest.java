package com.bancoxyz.cuentas;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.bancoxyz.cuentas.pago.EventoDePago;
import com.bancoxyz.cuentas.pago.ServicioDePagos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Pagos solicitados por pagos-service: se aplican en una transaccion local y el resultado sale por el outbox. */
class PagosTest extends PruebaDeCuentas {

    @Autowired
    private ServicioDePagos servicio;

    private static EventoDePago pago(String operacion, long cliente, Long origen, Long destino, String monto) {
        return new EventoDePago(1, UUID.randomUUID().toString(), EventoDePago.SOLICITADO, UUID.randomUUID().toString(),
                Instant.now(), operacion, cliente, 14L, origen, destino, new BigDecimal(monto), "CLP", "Luz");
    }

    private String resultado(EventoDePago pago) {
        return jdbc.sql("select resultado || coalesce(':' || motivo, '') from cuentas.pago_aplicado where pago_id = :p")
                .param("p", pago.pagoId()).query(String.class).single();
    }

    @Test
    @DisplayName("Transferencia: debito y credito en la misma transaccion, dos movimientos y TransaccionCompletada")
    void transferencia() {
        EventoDePago pago = pago(EventoDePago.TRANSFERENCIA, DIANA, 101L, 102L, "1500");

        servicio.aplicar(pago);

        assertThat(resultado(pago)).isEqualTo("COMPLETADA");
        assertThat(saldo(101)).isEqualTo("6540");
        assertThat(saldo(102)).isEqualTo("6500");
        assertThat(jdbc.sql("select tipo from cuentas.operacion order by tipo").query(String.class).list())
                .containsExactly("TRANSF_ENVIADA", "TRANSF_RECIBIDA");
        assertThat(jdbc.sql("select topico || ':' || tipo from cuentas.outbox").query(String.class).list())
                .containsExactly("cuentas.movimientos:TransaccionCompletada");
    }

    @Test
    @DisplayName("El mismo evento entregado dos veces se aplica una sola vez")
    void idempotente() {
        EventoDePago pago = pago(EventoDePago.TRANSFERENCIA, DIANA, 101L, 102L, "1000");
        servicio.aplicar(pago);
        servicio.aplicar(pago);
        assertThat(saldo(101)).isEqualTo("7040");
        assertThat(jdbc.sql("select count(*) from cuentas.outbox").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("Reglas: saldo insuficiente, cuenta ajena, misma cuenta, destino inexistente y cuenta de prestamo")
    void rechazos() {
        EventoDePago sinSaldo = pago(EventoDePago.TRANSFERENCIA, DIANA, 101L, 102L, "9000");
        EventoDePago ajena = pago(EventoDePago.TRANSFERENCIA, DIANA, 102L, 101L, "100");
        EventoDePago misma = pago(EventoDePago.TRANSFERENCIA, DIANA, 101L, 101L, "100");
        EventoDePago sinDestino = pago(EventoDePago.TRANSFERENCIA, DIANA, 101L, 999L, "100");
        EventoDePago prestamo = pago(EventoDePago.PAGO_SERVICIO, JANE, 103L, null, "100");
        for (EventoDePago p : new EventoDePago[] {sinSaldo, ajena, misma, sinDestino, prestamo}) {
            servicio.aplicar(p);
        }
        assertThat(resultado(sinSaldo)).isEqualTo("RECHAZADA:SALDO_INSUFICIENTE");
        assertThat(resultado(ajena)).isEqualTo("RECHAZADA:CUENTA_AJENA");
        assertThat(resultado(misma)).isEqualTo("RECHAZADA:MISMA_CUENTA");
        assertThat(resultado(sinDestino)).isEqualTo("RECHAZADA:CUENTA_DESTINO_NO_EXISTE");
        assertThat(resultado(prestamo)).isEqualTo("RECHAZADA:CUENTA_NO_ADMITE_CARGOS");
        assertThat(saldo(101)).isEqualTo("8040");
        assertThat(jdbc.sql("select count(*) from cuentas.outbox where tipo = 'TransaccionRechazada'").query(Long.class).single())
                .isEqualTo(5);
    }

    @Test
    @DisplayName("Deposito acredita y pago de servicio debita, cada uno con su movimiento")
    void depositoYPagoDeServicio() {
        EventoDePago deposito = pago(EventoDePago.DEPOSITO, DIANA, null, 105L, "3000");
        EventoDePago servicioLuz = pago(EventoDePago.PAGO_SERVICIO, DIANA, 105L, null, "500");
        servicio.aplicar(deposito);
        servicio.aplicar(servicioLuz);
        assertThat(saldo(105)).isEqualTo("14572");
        assertThat(jdbc.sql("select descripcion from cuentas.operacion where tipo = 'PAGO_SERVICIO'").query(String.class).single())
                .isEqualTo("Pago de servicio Luz");
    }
}
