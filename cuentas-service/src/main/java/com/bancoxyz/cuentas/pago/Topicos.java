package com.bancoxyz.cuentas.pago;

/** Topicos de Kafka que usa cuentas-service. Cada servicio es dueno de los que produce. */
public final class Topicos {

    /** Lo produce pagos-service: PagoSolicitado (transferencia, deposito o pago de servicio). */
    public static final String PAGOS_SOLICITADOS = "pagos.solicitados";
    /** Lo produce cuentas-service: TransaccionCompletada o TransaccionRechazada (tambien los retiros en cajero). */
    public static final String MOVIMIENTOS = "cuentas.movimientos";
    /** Alertas de seguridad de todo el banco; aqui: TarjetaBloqueada. */
    public static final String SEGURIDAD_ALERTAS = "seguridad.alertas";

    private Topicos() {
    }
}
