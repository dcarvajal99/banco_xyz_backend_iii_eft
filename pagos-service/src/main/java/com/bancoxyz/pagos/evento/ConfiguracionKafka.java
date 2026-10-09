package com.bancoxyz.pagos.evento;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.backoff.FixedBackOff;

/** Topicos que produce o consume pagos-service y manejo de errores del consumidor. */
@Configuration
@EnableScheduling
public class ConfiguracionKafka {

    @Bean
    public NewTopic topicoPagos(@Value("${banco.kafka.particiones:3}") int particiones) {
        return TopicBuilder.name(Topicos.PAGOS_SOLICITADOS).partitions(particiones).replicas(1).build();
    }

    @Bean
    public NewTopic dltMovimientos(@Value("${banco.kafka.particiones:3}") int particiones) {
        return TopicBuilder.name(Topicos.MOVIMIENTOS + ".DLT").partitions(particiones).replicas(1).build();
    }

    @Bean
    public NewTopic topicoMovimientos(@Value("${banco.kafka.particiones:3}") int particiones) {
        return TopicBuilder.name(Topicos.MOVIMIENTOS).partitions(particiones).replicas(1).build();
    }

    @Bean
    public NewTopic topicoAlertas(@Value("${banco.kafka.particiones:3}") int particiones) {
        return TopicBuilder.name(Topicos.SEGURIDAD_ALERTAS).partitions(particiones).replicas(1).build();
    }

    /**
     * Un evento que falla se reintenta 2 veces (1 s) y despues va a {@code <topico>.DLT} con el error en los
     * encabezados, sin frenar la particion. Un mensaje ilegible va directo a la DLT: reintentarlo no lo arregla.
     */
    @Bean
    public DefaultErrorHandler manejadorDeErroresKafka(KafkaTemplate<String, String> kafka) {
        DeadLetterPublishingRecoverer alDlt = new DeadLetterPublishingRecoverer(kafka,
                (registro, error) -> new TopicPartition(registro.topic() + ".DLT", registro.partition()));
        DefaultErrorHandler manejador = new DefaultErrorHandler(alDlt, new FixedBackOff(1000L, 2L));
        manejador.addNotRetryableExceptions(EventoInvalido.class);
        return manejador;
    }
}
