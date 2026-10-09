package com.bancoxyz.cuentas.cierre;

/**
 * Conjunto de datos que publica el batch en el esquema migracion: tabla y archivo legacy de origen.
 * Cada conjunto puede venir de una ejecucion distinta (un cierre completo o una corrida individual posterior).
 */
public enum Conjunto {

    INTERESES("cierre_cuenta", "intereses_trimestrales.csv"),
    MOVIMIENTOS("movimiento_anual", "estados_financieros_anuales.csv"),
    TRANSACCIONES("resumen_diario", "movimientos_financieros_diarios.csv");

    private final String tabla;
    private final String archivo;

    Conjunto(String tabla, String archivo) {
        this.tabla = tabla;
        this.archivo = archivo;
    }

    public String tabla() {
        return tabla;
    }

    public String archivo() {
        return archivo;
    }
}
