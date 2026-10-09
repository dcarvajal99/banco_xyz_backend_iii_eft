package com.bancoxyz.cuentas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bancoxyz.cuentas.pago.EventoInvalido;
import com.bancoxyz.cuentas.pago.LectorDeEventos;
import com.bancoxyz.cuentas.pago.VersionNoSoportada;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Contrato del evento PagoSolicitado: tolerante a campos nuevos, estricto con lo ilegible y con versiones futuras. */
class EventosTest {

    private final LectorDeEventos lector = new LectorDeEventos(new ObjectMapper().registerModule(new JavaTimeModule()));

    private static ConsumerRecord<String, String> registro(String json) {
        return new ConsumerRecord<>("pagos.solicitados", 0, 7L, "4", json);
    }

    @Test
    @DisplayName("Un campo que este servicio no conoce no rompe la lectura")
    void tolerante() {
        var evento = lector.leer(registro("{\"version\":1,\"eventoId\":\"e1\",\"tipo\":\"PagoSolicitado\",\"pagoId\":\"p1\","
                + "\"operacion\":\"DEPOSITO\",\"monto\":100,\"canal\":\"WEB\",\"nuevo\":true}"));
        assertThat(evento.pagoId()).isEqualTo("p1");
    }

    @Test
    @DisplayName("Ilegible o sin datos obligatorios: EventoInvalido (va a la DLT sin reintentos)")
    void ilegible() {
        assertThatThrownBy(() -> lector.leer(registro("no es json"))).isInstanceOf(EventoInvalido.class);
        assertThatThrownBy(() -> lector.leer(registro("{\"version\":1,\"eventoId\":\"e1\"}"))).isInstanceOf(EventoInvalido.class);
    }

    @Test
    @DisplayName("Una version mas nueva que la que se entiende no se adivina: VersionNoSoportada")
    void versionFutura() {
        assertThatThrownBy(() -> lector.leer(registro("{\"version\":2,\"eventoId\":\"e1\",\"pagoId\":\"p1\"}")))
                .isInstanceOf(VersionNoSoportada.class);
    }
}
