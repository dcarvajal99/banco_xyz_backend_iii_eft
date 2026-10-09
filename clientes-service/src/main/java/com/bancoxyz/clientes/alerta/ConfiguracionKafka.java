package com.bancoxyz.clientes.alerta;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Topico que produce este servicio. Lo crea KafkaAdmin al arrancar si no existe (con las mismas particiones en todos
 * los servicios que lo declaran: crearlo dos veces no falla). {@code @EnableScheduling}: el publicador del outbox.
 */
@Configuration
@EnableScheduling
public class ConfiguracionKafka {

    @Bean
    public NewTopic topicoAlertas(@Value("${banco.kafka.particiones:3}") int particiones) {
        return TopicBuilder.name(Topicos.SEGURIDAD_ALERTAS).partitions(particiones).replicas(1).build();
    }
}
