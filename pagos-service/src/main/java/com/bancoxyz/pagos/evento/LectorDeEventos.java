package com.bancoxyz.pagos.evento;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;

/**
 * Lee un resultado de cuentas-service. Tolera campos nuevos (un productor puede agregar campos opcionales sin romper a nadie) y manda
 * a la DLT un mensaje ilegible o de una version del contrato mas nueva que la que este servicio entiende.
 */
@Component
public class LectorDeEventos {

    private final ObjectMapper json;
    private final ObjectReader lectorTolerante;

    public LectorDeEventos(ObjectMapper json) {
        this.json = json;
        this.lectorTolerante = json.readerFor(EventoDeMovimiento.class).without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public EventoDeMovimiento leer(ConsumerRecord<String, String> registro) {
        String origen = registro.topic() + "-" + registro.partition() + "@" + registro.offset();
        JsonNode nodo;
        try {
            nodo = json.readTree(registro.value());
        } catch (Exception ilegible) {
            throw new EventoInvalido("Mensaje ilegible en " + origen, ilegible);
        }
        if (nodo == null || !nodo.isObject()) {
            throw new EventoInvalido("Mensaje ilegible en " + origen, null);
        }
        int version = nodo.path("version").asInt(1);
        if (version < 1 || version > EventoDeMovimiento.VERSION_ACTUAL) {
            throw new VersionNoSoportada(version, origen);
        }
        EventoDeMovimiento evento;
        try {
            evento = lectorTolerante.readValue(nodo);
        } catch (Exception malFormado) {
            throw new EventoInvalido("Evento con campos invalidos en " + origen, malFormado);
        }
        if (evento.eventoId() == null || evento.tipo() == null) {
            throw new EventoInvalido("Evento sin eventoId o tipo en " + origen, null);
        }
        return evento;
    }
}
