package com.bancoxyz.clientes.alerta;

import com.bancoxyz.clientes.aviso.ConsumidorDeEventos;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Topicos de este servicio y manejo de errores de sus consumidores. Lo crea KafkaAdmin al arrancar si no existe (con las mismas particiones en todos
 * los servicios que lo declaran: crearlo dos veces no falla). {@code @EnableScheduling}: el publicador del outbox.
 */
@Configuration
@EnableScheduling
public class ConfiguracionKafka {

    @Bean
    public NewTopic dltMovimientos(@Value("${banco.kafka.particiones:3}") int particiones) {
        return TopicBuilder.name(Topicos.MOVIMIENTOS + ".DLT").partitions(particiones).replicas(1).build();
    }

    @Bean
    public NewTopic dltAlertas(@Value("${banco.kafka.particiones:3}") int particiones) {
        return TopicBuilder.name(Topicos.SEGURIDAD_ALERTAS + ".DLT").partitions(particiones).replicas(1).build();
    }

    /** 2 reintentos (1 s) y despues a {@code <topico>.DLT}; un evento ilegible va directo. */
    @Bean
    public DefaultErrorHandler manejadorDeErroresKafka(KafkaTemplate<String, String> kafka) {
        DeadLetterPublishingRecoverer alDlt = new DeadLetterPublishingRecoverer(kafka,
                (registro, error) -> new TopicPartition(registro.topic() + ".DLT", registro.partition()));
        DefaultErrorHandler manejador = new DefaultErrorHandler(alDlt, new FixedBackOff(1000L, 2L));
        manejador.addNotRetryableExceptions(ConsumidorDeEventos.EventoIlegible.class);
        return manejador;
    }

    @Bean
    public NewTopic topicoAlertas(@Value("${banco.kafka.particiones:3}") int particiones) {
        return TopicBuilder.name(Topicos.SEGURIDAD_ALERTAS).partitions(particiones).replicas(1).build();
    }
}
