package com.bancoxyz.cuentas.pago;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Escucha pagos.solicitados. Un tipo de evento desconocido se ignora; uno ilegible termina en la DLT. */
@Component
public class ConsumidorDePagos {

    private static final Logger log = LoggerFactory.getLogger(ConsumidorDePagos.class);

    private final ServicioDePagos servicio;
    private final LectorDeEventos lector;

    public ConsumidorDePagos(ServicioDePagos servicio, LectorDeEventos lector) {
        this.servicio = servicio;
        this.lector = lector;
    }

    @KafkaListener(id = "cuentas-pagos", topics = Topicos.PAGOS_SOLICITADOS, groupId = "${spring.application.name}")
    public void solicitado(ConsumerRecord<String, String> registro) {
        EventoDePago evento = lector.leer(registro);
        if (EventoDePago.SOLICITADO.equals(evento.tipo())) {
            servicio.aplicar(evento);
        } else {
            log.info("Evento {} ignorado en {}: cuentas-service no reacciona a este tipo", evento.tipo(), registro.topic());
        }
    }
}
