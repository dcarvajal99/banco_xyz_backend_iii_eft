package com.bancoxyz.clientes.aviso;

import com.bancoxyz.clientes.alerta.Topicos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Escucha transacciones y alertas para avisar al cliente. Lee el JSON como arbol: solo usa los campos que necesita, asi
 * un productor puede agregar campos sin romper a este consumidor. Un mensaje ilegible va a la DLT.
 */
@Component
public class ConsumidorDeEventos {

    private final ServicioDeAvisos servicio;
    private final ObjectMapper json;

    public ConsumidorDeEventos(ServicioDeAvisos servicio, ObjectMapper json) {
        this.servicio = servicio;
        this.json = json;
    }

    @KafkaListener(id = "clientes-movimientos", topics = Topicos.MOVIMIENTOS, groupId = "${spring.application.name}")
    public void movimiento(ConsumerRecord<String, String> registro) {
        servicio.movimiento(leer(registro));
    }

    @KafkaListener(id = "clientes-alertas", topics = Topicos.SEGURIDAD_ALERTAS, groupId = "${spring.application.name}")
    public void alerta(ConsumerRecord<String, String> registro) {
        servicio.alerta(leer(registro));
    }

    private JsonNode leer(ConsumerRecord<String, String> registro) {
        try {
            JsonNode nodo = json.readTree(registro.value());
            if (nodo == null || !nodo.hasNonNull("eventoId") || !nodo.hasNonNull("tipo")) {
                throw new EventoIlegible(registro.topic() + "@" + registro.offset());
            }
            return nodo;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new EventoIlegible(registro.topic() + "@" + registro.offset());
        }
    }

    /** No se reintenta: va directo a la DLT. */
    public static class EventoIlegible extends RuntimeException {
        public EventoIlegible(String origen) {
            super("Evento ilegible en " + origen);
        }
    }
}
