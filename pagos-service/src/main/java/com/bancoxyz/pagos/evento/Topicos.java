package com.bancoxyz.pagos.evento;

/** Topicos de Kafka que usa pagos-service. */
public final class Topicos {

    /** Lo produce pagos-service: PagoSolicitado. */
    public static final String PAGOS_SOLICITADOS = "pagos.solicitados";
    /** Lo produce cuentas-service: TransaccionCompletada / TransaccionRechazada. */
    public static final String MOVIMIENTOS = "cuentas.movimientos";
    /** Aqui: OperacionSospechosa cuando una regla de fraude rechaza un pago. */
    public static final String SEGURIDAD_ALERTAS = "seguridad.alertas";

    private Topicos() {
    }
}
