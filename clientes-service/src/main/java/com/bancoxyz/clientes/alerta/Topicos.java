package com.bancoxyz.clientes.alerta;

/** Topicos de Kafka que produce clientes-service. */
public final class Topicos {

    /** Alertas de seguridad de todo el banco: usuario bloqueado, tarjeta bloqueada, operacion sospechosa. */
    public static final String SEGURIDAD_ALERTAS = "seguridad.alertas";

    private Topicos() {
    }
}
