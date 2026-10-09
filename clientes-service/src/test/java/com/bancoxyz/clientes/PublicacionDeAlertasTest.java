package com.bancoxyz.clientes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;

import com.bancoxyz.clientes.outbox.PublicadorDeOutbox;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** La alerta del outbox llega al topico seguridad.alertas con su tipo en el encabezado; si Kafka falla, queda pendiente. */
@TestPropertySource(properties = {"banco.outbox.publicar=true", "banco.outbox.intervalo-ms=3600000"})
class PublicacionDeAlertasTest extends PruebaDeClientes {

    @MockitoBean
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private PublicadorDeOutbox publicador;

    private void bloquearDiana() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/v1/autenticacion/usuarios").with(bancoAuth()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"usuario\":\"diana.prince\",\"password\":\"mala\"}"));
        }
    }

    private long pendientes() {
        return jdbc.sql("select count(*) from clientes.outbox where publicado_en is null").query(Long.class).single();
    }

    @Test
    @DisplayName("Kafka confirma: la alerta sale al topico seguridad.alertas con clave = cliente y queda marcada")
    @SuppressWarnings("unchecked")
    void publica() throws Exception {
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(
                (SendResult<String, String>) org.mockito.Mockito.mock(SendResult.class)));
        bloquearDiana();

        publicador.publicarPendientes();

        ArgumentCaptor<ProducerRecord<String, String>> enviado = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(enviado.capture());
        assertThat(enviado.getValue().topic()).isEqualTo("seguridad.alertas");
        assertThat(enviado.getValue().key()).isEqualTo(String.valueOf(DIANA));
        assertThat(new String(enviado.getValue().headers().lastHeader("tipo").value())).isEqualTo("UsuarioBloqueado");
        assertThat(pendientes()).isZero();
    }

    @Test
    @DisplayName("Kafka no responde: la alerta no se pierde, sigue pendiente en el outbox para la proxima pasada")
    @SuppressWarnings("unchecked")
    void kafkaCaido() throws Exception {
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.failedFuture(
                new org.apache.kafka.common.errors.TimeoutException("broker caido")));
        bloquearDiana();

        publicador.publicarPendientes();

        assertThat(pendientes()).isEqualTo(1);
    }
}
