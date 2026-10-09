package com.bancoxyz.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Una cuenta del cierre publicado: la foto consolidada que copian los microservicios.
 *
 * <p>{@code cuenta_interes} guarda todas las filas validas del archivo, y el archivo legacy repite la
 * misma cuenta muchas veces con datos distintos. Elegir cual fila representa a la cuenta es una regla de
 * la migracion y vive aqui, en el batch: hasta la semana 8 la aplicaba el core leyendo el texto de una
 * observacion, un acoplamiento fragil con otro modulo. Ahora cuentas-service lee una fila por cuenta y no
 * necesita conocer esa regla.</p>
 *
 * <p>Regla: la primera aparicion de la cuenta en el archivo (menor {@code linea_origen}). Con particiones
 * el orden de escritura no es el del archivo, por eso se decide por la linea y no por el id.</p>
 */
@Entity
@Table(name = "cierre_cuenta",
        uniqueConstraints = @UniqueConstraint(name = "uk_cierre_cuenta", columnNames = {"job_execution_id", "cuenta_id"}),
        indexes = @Index(name = "idx_cierre_cuenta_ejecucion", columnList = "job_execution_id"))
public class CierreCuenta {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "cierre_cuenta_seq")
    @SequenceGenerator(name = "cierre_cuenta_seq", sequenceName = "cierre_cuenta_seq", allocationSize = 50)
    private Long id;

    @Column(name = "job_execution_id", nullable = false)
    private Long jobExecutionId;

    @Column(name = "cuenta_id", nullable = false)
    private Long cuentaId;

    /** Id de {@link ClienteMigrado}; vacio si el titular viene sin identificar. */
    @Column(name = "cliente_id")
    private Long clienteId;

    @Column(nullable = false, length = 120)
    private String titular;

    @Column(nullable = false, length = 20)
    private String tipo;

    @Column(name = "saldo_inicial", nullable = false, precision = 18, scale = 2)
    private BigDecimal saldoInicial;

    @Column(name = "tasa_mensual", nullable = false, precision = 8, scale = 5)
    private BigDecimal tasaMensual;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal interes;

    @Column(name = "saldo_final", nullable = false, precision = 18, scale = 2)
    private BigDecimal saldoFinal;

    @Column(nullable = false)
    private boolean anomalia;

    @Column(length = 255)
    private String observacion;

    @Column(name = "linea_origen", nullable = false)
    private int lineaOrigen;

    /** Cuantas filas validas trajo el archivo para esta cuenta (1 si no se repite). */
    @Column(name = "filas_en_origen", nullable = false)
    private int filasEnOrigen;

    @Column(name = "publicado_en", nullable = false)
    private LocalDateTime publicadoEn = LocalDateTime.now();

    public CierreCuenta() {
        // Constructor requerido por JPA.
    }

    /** La fila canonica de la cuenta, con el cliente ya resuelto. */
    public static CierreCuenta desde(CuentaInteres fila, Long clienteId, int filasEnOrigen) {
        CierreCuenta cierre = new CierreCuenta();
        cierre.jobExecutionId = fila.getJobExecutionId();
        cierre.cuentaId = fila.getCuentaId();
        cierre.clienteId = clienteId;
        cierre.titular = fila.getNombre();
        cierre.tipo = fila.getTipo();
        cierre.saldoInicial = fila.getSaldoInicial();
        cierre.tasaMensual = fila.getTasaMensual();
        cierre.interes = fila.getInteres();
        cierre.saldoFinal = fila.getSaldoFinal();
        cierre.anomalia = fila.isAnomalia();
        cierre.observacion = fila.getObservacion();
        cierre.lineaOrigen = fila.getLineaOrigen();
        cierre.filasEnOrigen = filasEnOrigen;
        return cierre;
    }

    public Long getId() {
        return id;
    }

    public Long getJobExecutionId() {
        return jobExecutionId;
    }

    public Long getCuentaId() {
        return cuentaId;
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

    public BigDecimal getSaldoInicial() {
        return saldoInicial;
    }

    public BigDecimal getTasaMensual() {
        return tasaMensual;
    }

    public BigDecimal getInteres() {
        return interes;
    }

    public BigDecimal getSaldoFinal() {
        return saldoFinal;
    }

    public boolean isAnomalia() {
        return anomalia;
    }

    public String getObservacion() {
        return observacion;
    }

    public int getLineaOrigen() {
        return lineaOrigen;
    }

    public int getFilasEnOrigen() {
        return filasEnOrigen;
    }

    public LocalDateTime getPublicadoEn() {
        return publicadoEn;
    }

    @Override
    public boolean equals(Object otro) {
        return this == otro || otro instanceof CierreCuenta cierre
                && Objects.equals(cuentaId, cierre.cuentaId) && Objects.equals(jobExecutionId, cierre.jobExecutionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(cuentaId, jobExecutionId);
    }

    @Override
    public String toString() {
        return "CierreCuenta{cuentaId=" + cuentaId + ", clienteId=" + clienteId + ", tipo='" + tipo
                + "', saldoFinal=" + saldoFinal + "}";
    }
}
