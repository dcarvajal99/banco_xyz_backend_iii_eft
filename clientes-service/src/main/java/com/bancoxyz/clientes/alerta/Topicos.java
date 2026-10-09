package com.bancoxyz.clientes.alerta;

/** Topicos de Kafka que usa clientes-service. */
public final class Topicos {

    /** Alertas de seguridad de todo el banco: usuario bloqueado, tarjeta bloqueada, operacion sospechosa. */
    public static final String SEGURIDAD_ALERTAS = "seguridad.alertas";

    /** Lo produce cuentas-service: TransaccionCompletada / TransaccionRechazada (se consume para avisar). */
    public static final String MOVIMIENTOS = "cuentas.movimientos";

    private Topicos() {
    }
}
