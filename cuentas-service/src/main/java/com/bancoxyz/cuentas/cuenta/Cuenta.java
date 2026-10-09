package com.bancoxyz.cuentas.cuenta;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Cuenta del banco. Nace del cierre publicado por el batch (MIGRACION) o de una apertura en linea (APERTURA).
 * El saldo disponible lo mueven los retiros y los pagos; el saldo al cierre queda como referencia del batch.
 */
@Entity
@Table(schema = "cuentas", name = "cuenta")
public class Cuenta {

    public static final String TIPO_AHORRO = "ahorro";
    public static final String ACTIVA = "ACTIVA";
    public static final String BLOQUEADA = "BLOQUEADA";
    public static final String CERRADA = "CERRADA";

    @Id
    private Long id;

    @Column(name = "cliente_id")
    private Long clienteId;

    @Column(nullable = false, length = 120)
    private String titular;

    @Column(nullable = false, length = 20)
    private String tipo;

    @Column(length = 40)
    private String alias;

    @Column(nullable = false, length = 12)
    private String estado;

    @Column(nullable = false, length = 12)
    private String origen;

    @Column(name = "saldo_disponible", nullable = false, precision = 18, scale = 2)
    private BigDecimal saldoDisponible;

    @Column(name = "saldo_cierre", nullable = false, precision = 18, scale = 2)
    private BigDecimal saldoCierre;

    @Column(name = "tasa_mensual", nullable = false, precision = 8, scale = 5)
    private BigDecimal tasaMensual;

    @Column(name = "interes_cierre", nullable = false, precision = 18, scale = 2)
    private BigDecimal interesCierre;

    @Column(nullable = false)
    private boolean anomalia;

    @Column(length = 255)
    private String observacion;

    @Column(name = "cierre_job_execution_id")
    private Long cierreJobExecutionId;

    @Column(name = "abierta_en", nullable = false)
    private LocalDateTime abiertaEn;

    @Column(name = "cerrada_en")
    private LocalDateTime cerradaEn;

    @Column(name = "motivo_cierre", length = 120)
    private String motivoCierre;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "actualizada_en", nullable = false)
    private LocalDateTime actualizadaEn;

    protected Cuenta() {
    }

    /** Cuenta del cierre publicado por el batch. */
    public static Cuenta migrada(long id, Long clienteId, String titular, String tipo, BigDecimal saldo, BigDecimal tasa,
                                 BigDecimal interes, boolean anomalia, String observacion, long jobExecutionId,
                                 LocalDateTime ahora) {
        Cuenta c = nueva(id, clienteId, titular, tipo, saldo, tasa, "MIGRACION", ahora);
        c.interesCierre = interes;
        c.anomalia = anomalia;
        c.observacion = observacion;
        c.cierreJobExecutionId = jobExecutionId;
        return c;
    }

    /** Cuenta abierta en linea por un ejecutivo: parte en cero. */
    public static Cuenta abierta(long id, long clienteId, String titular, String tipo, String alias, BigDecimal tasa,
                                 LocalDateTime ahora) {
        Cuenta c = nueva(id, clienteId, titular, tipo, BigDecimal.ZERO.setScale(2), tasa, "APERTURA", ahora);
        c.alias = alias;
        c.interesCierre = BigDecimal.ZERO.setScale(2);
        return c;
    }

    private static Cuenta nueva(long id, Long clienteId, String titular, String tipo, BigDecimal saldo, BigDecimal tasa,
                                String origen, LocalDateTime ahora) {
        Cuenta c = new Cuenta();
        c.id = id;
        c.clienteId = clienteId;
        c.titular = titular;
        c.tipo = tipo;
        c.estado = ACTIVA;
        c.origen = origen;
        c.saldoDisponible = saldo;
        c.saldoCierre = saldo;
        c.tasaMensual = tasa;
        c.abiertaEn = ahora;
        c.actualizadaEn = ahora;
        return c;
    }

    /** Siempre con la fila bloqueada y las reglas ya verificadas; el check de la base es la red final. */
    public void descontar(BigDecimal monto, LocalDateTime ahora) {
        if (saldoDisponible.compareTo(monto) < 0) {
            throw new IllegalStateException("Descuento sobre saldo insuficiente en la cuenta " + id);
        }
        saldoDisponible = saldoDisponible.subtract(monto);
        actualizadaEn = ahora;
    }

    public void acreditar(BigDecimal monto, LocalDateTime ahora) {
        saldoDisponible = saldoDisponible.add(monto);
        actualizadaEn = ahora;
    }

    public void cambiarAlias(String nuevoAlias, LocalDateTime ahora) {
        alias = nuevoAlias;
        actualizadaEn = ahora;
    }

    /** ACTIVA o BLOQUEADA; una cuenta cerrada ya no cambia de estado. */
    public void cambiarEstado(String nuevoEstado, LocalDateTime ahora) {
        estado = nuevoEstado;
        actualizadaEn = ahora;
    }

    public void cerrar(String motivo, LocalDateTime ahora) {
        estado = CERRADA;
        motivoCierre = motivo;
        cerradaEn = ahora;
        actualizadaEn = ahora;
    }

    public boolean esAhorro() {
        return TIPO_AHORRO.equals(tipo);
    }

    public boolean estaActiva() {
        return ACTIVA.equals(estado);
    }

    public boolean estaCerrada() {
        return CERRADA.equals(estado);
    }

    public Long getId() {
        return id;
    }

    public Long getClienteId() {
        return clienteId;
    }

    public String getTitular() {
        return titular;
    }

    public String getTipo() {
        return tipo;
    }

    public String getAlias() {
        return alias;
    }

    public String getEstado() {
        return estado;
    }

    public String getOrigen() {
        return origen;
    }

    public BigDecimal getSaldoDisponible() {
        return saldoDisponible;
    }

    public BigDecimal getSaldoCierre() {
        return saldoCierre;
    }

    public BigDecimal getTasaMensual() {
        return tasaMensual;
    }

    public BigDecimal getInteresCierre() {
        return interesCierre;
    }

    public boolean isAnomalia() {
        return anomalia;
    }

    public String getObservacion() {
        return observacion;
    }

    public Long getCierreJobExecutionId() {
        return cierreJobExecutionId;
    }

    public LocalDateTime getAbiertaEn() {
        return abiertaEn;
    }

    public LocalDateTime getCerradaEn() {
        return cerradaEn;
    }

    public String getMotivoCierre() {
        return motivoCierre;
    }

    public long getVersion() {
        return version;
    }

    public LocalDateTime getActualizadaEn() {
        return actualizadaEn;
    }
}
