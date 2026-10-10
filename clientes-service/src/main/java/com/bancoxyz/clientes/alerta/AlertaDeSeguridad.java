package com.bancoxyz.clientes.alerta;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import com.bancoxyz.clientes.autenticacion.Usuario;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Sobre de una alerta de seguridad en el topico {@value Topicos#SEGURIDAD_ALERTAS}.
 *
 * <p>Lo producen clientes-service (usuario bloqueado), cuentas-service (tarjeta bloqueada por PIN) y pagos-service
 * (operacion rechazada por fraude); lo consume este servicio para avisar al cliente. Cada servicio tiene su copia del
 * registro: el acuerdo es el JSON del evento.</p>
 *
 * @param referencia lo que identifica el hecho para el cliente (usuario, ultimos 4 de la tarjeta, id de la operacion)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"version", "eventoId", "tipo"})
public record AlertaDeSeguridad(int version, String eventoId, String tipo, Instant ocurridoEn, String origen,
                                String severidad, Long clienteId, Long usuarioId, Long cuentaId, String referencia,
                                String detalle) {

    public static final int VERSION_ACTUAL = 1;
    public static final String USUARIO_BLOQUEADO = "UsuarioBloqueado";

    public static AlertaDeSeguridad usuarioBloqueado(Usuario usuario, LocalDateTime ahora, Duration bloqueo, Clock reloj) {
        return new AlertaDeSeguridad(VERSION_ACTUAL, UUID.randomUUID().toString(), USUARIO_BLOQUEADO, reloj.instant(),
                "clientes-service", "ALTA", usuario.getClienteId(), usuario.getId(), null, usuario.getUsuario(),
                "Usuario bloqueado por intentos fallidos hasta las " + ahora.plus(bloqueo).toLocalTime().withNano(0));
    }

    /** Clave de Kafka: el cliente, para que las alertas de una persona lleguen en orden. */
    public String clave() {
        return clienteId == null ? "sin-cliente" : String.valueOf(clienteId);
    }
}
