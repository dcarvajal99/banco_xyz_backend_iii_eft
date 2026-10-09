package com.bancoxyz.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * Totales de control de un archivo legacy contra lo que la migracion dejo en la base.
 *
 * <p>Es la conciliacion que el operador del mainframe hacia a mano al cierre: cuantas lineas trajo el
 * archivo y a donde fue a parar cada una. Una fila del archivo solo puede terminar migrada, filtrada por
 * duplicada u omitida por dato sucio; si la suma no da el total, se perdio o se duplico informacion y la
 * corrida no se publica.</p>
 */
@Entity
@Table(name = "conciliacion", indexes = @Index(name = "idx_conciliacion_ejecucion", columnList = "job_execution_id"))
public class Conciliacion {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "conciliacion_seq")
    @SequenceGenerator(name = "conciliacion_seq", sequenceName = "conciliacion_seq", allocationSize = 10)
    private Long id;

    @Column(name = "job_execution_id", nullable = false)
    private Long jobExecutionId;

    @Column(nullable = false, length = 60)
    private String paso;

    @Column(nullable = false, length = 60)
    private String archivo;

    /** Lineas de datos del archivo (sin la cabecera), contadas directamente del archivo. */
    @Column(name = "lineas_archivo", nullable = false)
    private long lineasArchivo;

    /** Lineas que entraron al Step (leidas + omitidas al leer). */
    @Column(nullable = false)
    private long leidas;

    @Column(nullable = false)
    private long migradas;

    @Column(nullable = false)
    private long filtradas;

    @Column(nullable = false)
    private long omitidas;

    /** Filas de la tabla destino a nombre de esta ejecucion. */
    @Column(name = "filas_en_tabla", nullable = false)
    private long filasEnTabla;

    /** Rechazos de este archivo registrados en la bitacora (omitidos + filtrados). */
    @Column(name = "filas_en_bitacora", nullable = false)
    private long filasEnBitacora;

    @Column(nullable = false)
    private boolean cuadra;

    @Column(length = 500)
    private String diferencias;

    @Column(name = "conciliado_en", nullable = false)
    private LocalDateTime conciliadoEn = LocalDateTime.now();

    public Conciliacion() {
        // Constructor requerido por JPA.
    }

    public Conciliacion(Long jobExecutionId, String paso, String archivo, long lineasArchivo, long leidas,
                        long migradas, long filtradas, long omitidas, long filasEnTabla, long filasEnBitacora,
                        String diferencias) {
        this.jobExecutionId = jobExecutionId;
        this.paso = paso;
        this.archivo = archivo;
        this.lineasArchivo = lineasArchivo;
        this.leidas = leidas;
        this.migradas = migradas;
        this.filtradas = filtradas;
        this.omitidas = omitidas;
        this.filasEnTabla = filasEnTabla;
        this.filasEnBitacora = filasEnBitacora;
        this.cuadra = diferencias == null;
        this.diferencias = diferencias == null || diferencias.length() <= 500 ? diferencias : diferencias.substring(0, 500);
    }

    public Long getId() {
        return id;
    }

    public Long getJobExecutionId() {
        return jobExecutionId;
    }

    public String getPaso() {
        return paso;
    }

    public String getArchivo() {
        return archivo;
    }

    public long getLineasArchivo() {
        return lineasArchivo;
    }

    public long getLeidas() {
        return leidas;
    }

    public long getMigradas() {
        return migradas;
    }

    public long getFiltradas() {
        return filtradas;
    }

    public long getOmitidas() {
        return omitidas;
    }

    public long getFilasEnTabla() {
        return filasEnTabla;
    }

    public long getFilasEnBitacora() {
        return filasEnBitacora;
    }

    public boolean isCuadra() {
        return cuadra;
    }

    public String getDiferencias() {
        return diferencias;
    }

    public LocalDateTime getConciliadoEn() {
        return conciliadoEn;
    }
}
