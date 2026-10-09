package com.bancoxyz.pagos.evento;

import com.bancoxyz.pagos.pago.ServicioDePagos;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Resultado de cada pago aplicado por cuentas-service. Un mensaje ilegible va a la DLT sin reintentos. */
@Component
public class ConsumidorDeMovimientos {

    private final ServicioDePagos servicio;
    private final LectorDeEventos lector;

    public ConsumidorDeMovimientos(ServicioDePagos servicio, LectorDeEventos lector) {
        this.servicio = servicio;
        this.lector = lector;
    }

    @KafkaListener(id = "pagos-movimientos", topics = Topicos.MOVIMIENTOS, groupId = "${spring.application.name}")
    public void movimiento(ConsumerRecord<String, String> registro) {
        servicio.registrarResultado(lector.leer(registro));
    }
}
