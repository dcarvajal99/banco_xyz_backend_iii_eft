package com.bancoxyz.pagos.pago;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Un pago tal como lo ve el cliente: SOLICITADO -> COMPLETADO | RECHAZADO (un estado final no vuelve atras). */
@Entity
@Table(schema = "pagos", name = "pago")
public class Pago {

    public static final String TRANSFERENCIA = "TRANSFERENCIA";
    public static final String DEPOSITO = "DEPOSITO";
    public static final String PAGO_SERVICIO = "PAGO_SERVICIO";

    public enum Estado { SOLICITADO, COMPLETADO, RECHAZADO }

    @Id
    private String id;

    @Column(nullable = false, length = 20)
    private String operacion;

    @Column(name = "cliente_id", nullable = false)
    private long clienteId;

    @Column(name = "usuario_id", nullable = false)
    private long usuarioId;

    @Column(name = "cuenta_origen")
    private Long cuentaOrigen;

    @Column(name = "cuenta_destino")
    private Long cuentaDestino;

    @Column(length = 40)
    private String convenio;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal monto;

    @Column(nullable = false, length = 12)
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private Estado estado;

    @Column(length = 40)
    private String motivo;

    @Column(nullable = false, length = 10)
    private String validacion;

    @Column(nullable = false, length = 10)
    private String canal;

    @Column(name = "clave_idempotencia", nullable = false, length = 80)
    private String claveIdempotencia;

    @Column(name = "creado_en", nullable = false)
    private LocalDateTime creadoEn;

    @Column(name = "actualizado_en", nullable = false)
    private LocalDateTime actualizadoEn;

    protected Pago() {
    }

    public Pago(String id, String operacion, long clienteId, long usuarioId, Long cuentaOrigen, Long cuentaDestino,
                String convenio, BigDecimal monto, String validacion, String canal, String claveIdempotencia,
                LocalDateTime ahora) {
        this.id = id;
        this.operacion = operacion;
        this.clienteId = clienteId;
        this.usuarioId = usuarioId;
        this.cuentaOrigen = cuentaOrigen;
        this.cuentaDestino = cuentaDestino;
        this.convenio = convenio;
        this.monto = monto.setScale(2);
        this.estado = Estado.SOLICITADO;
        this.validacion = validacion;
        this.canal = canal;
        this.claveIdempotencia = claveIdempotencia;
        this.creadoEn = ahora;
        this.actualizadoEn = ahora;
    }

    /** @return false si ya estaba en un estado final (evento atrasado o repetido): no cambia nada */
    public boolean finalizar(Estado nuevo, String nuevoMotivo, LocalDateTime ahora) {
        if (estado != Estado.SOLICITADO) {
            return false;
        }
        estado = nuevo;
        motivo = nuevoMotivo;
        actualizadoEn = ahora;
        return true;
    }

    /** Mismo contenido que otra solicitud: decide si una Idempotency-Key repetida es un reintento o un conflicto. */
    public boolean mismaSolicitud(String otraOperacion, Long origen, Long destino, String otroConvenio, BigDecimal otroMonto) {
        return operacion.equals(otraOperacion) && Objects.equals(cuentaOrigen, origen) && Objects.equals(cuentaDestino, destino)
                && Objects.equals(convenio, otroConvenio) && monto.compareTo(otroMonto) == 0;
    }

    public String getId() {
        return id;
    }

    public String getOperacion() {
        return operacion;
    }

    public long getClienteId() {
        return clienteId;
    }

    public long getUsuarioId() {
        return usuarioId;
    }

    public Long getCuentaOrigen() {
        return cuentaOrigen;
    }

    public Long getCuentaDestino() {
        return cuentaDestino;
    }

    public String getConvenio() {
        return convenio;
    }

    public BigDecimal getMonto() {
        return monto;
    }

    public Estado getEstado() {
        return estado;
    }

    public String getMotivo() {
        return motivo;
    }

    public String getValidacion() {
        return validacion;
    }

    public String getCanal() {
        return canal;
    }

    public LocalDateTime getCreadoEn() {
        return creadoEn;
    }

    public LocalDateTime getActualizadoEn() {
        return actualizadoEn;
    }
}
