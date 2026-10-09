package com.bancoxyz.cuentas.outbox;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Transactional outbox de cuentas-service.
 *
 * <p>{@link #registrar} se llama dentro de la transaccion que cambia el estado (saldos, una tarjeta): el evento queda
 * guardado junto con el cambio, o no queda ninguno de los dos. {@link PublicadorDeOutbox} lo envia a Kafka despues.
 * Entrega al menos una vez: el consumidor descarta duplicados por {@code eventoId}.</p>
 */
@Component
public class Outbox {

    /** Evento guardado a la espera de ser publicado. */
    public record Pendiente(long id, String eventoId, String topico, String clave, String tipo, String payload) {
    }

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final Clock reloj;

    public Outbox(JdbcClient jdbc, ObjectMapper json, Clock reloj) {
        this.jdbc = jdbc;
        this.json = json;
        this.reloj = reloj;
    }

    public void registrar(String topico, String eventoId, String clave, String tipo, Object evento) {
        jdbc.sql("""
                        insert into cuentas.outbox (evento_id, topico, clave, tipo, payload, creado_en)
                        values (:evento, :topico, :clave, :tipo, :payload, :ahora)""")
                .param("evento", eventoId)
                .param("topico", topico)
                .param("clave", clave)
                .param("tipo", tipo)
                .param("payload", aJson(evento))
                .param("ahora", LocalDateTime.now(reloj))
                .update();
    }

    /**
     * Los mas antiguos sin publicar, bloqueados para esta transaccion. {@code skip locked}: con varias instancias del
     * servicio cada una toma filas distintas en vez de esperar o publicar dos veces.
     */
    public List<Pendiente> pendientes(int limite) {
        return jdbc.sql("""
                        select id, evento_id, topico, clave, tipo, payload from cuentas.outbox
                        where publicado_en is null order by id limit :limite for update skip locked""")
                .param("limite", limite)
                .query((fila, n) -> new Pendiente(fila.getLong("id"), fila.getString("evento_id"),
                        fila.getString("topico"), fila.getString("clave"), fila.getString("tipo"), fila.getString("payload")))
                .list();
    }

    public void marcarPublicado(long id) {
        jdbc.sql("update cuentas.outbox set publicado_en = :ahora where id = :id")
                .param("ahora", LocalDateTime.now(reloj)).param("id", id).update();
    }

    public long contarPendientes() {
        return jdbc.sql("select count(*) from cuentas.outbox where publicado_en is null").query(Long.class).single();
    }

    private String aJson(Object evento) {
        try {
            return json.writeValueAsString(evento);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar el evento", e);
        }
    }
}
