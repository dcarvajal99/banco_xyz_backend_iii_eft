package com.bancoxyz.cuentas.operacion;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Movimiento en linea sobre una cuenta: retiro en cajero o un lado (debito/credito) de un pago aplicado. */
@Entity
@Table(schema = "cuentas", name = "operacion")
public class Operacion {

    public static final String TIPO_RETIRO = "RETIRO";
    public static final String TIPO_TRANSFERENCIA_ENVIADA = "TRANSF_ENVIADA";
    public static final String TIPO_TRANSFERENCIA_RECIBIDA = "TRANSF_RECIBIDA";
    public static final String TIPO_DEPOSITO = "DEPOSITO";
    public static final String TIPO_PAGO_SERVICIO = "PAGO_SERVICIO";
    public static final String CANAL_CAJERO = "CAJERO";
    public static final String CANAL_PAGOS = "PAGOS";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cuenta_id", nullable = false)
    private Long cuentaId;

    @Column(name = "tarjeta_id")
    private Long tarjetaId;

    @Column(nullable = false, length = 20)
    private String tipo;

    @Column(nullable = false, length = 20)
    private String canal;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal monto;

    @Column(name = "saldo_resultante", nullable = false, precision = 18, scale = 2)
    private BigDecimal saldoResultante;

    @Column(name = "terminal_id", length = 40)
    private String terminalId;

    @Column(length = 120)
    private String descripcion;

    /** Id del pago en pagos-service (vacio en un retiro). */
    @Column(length = 36)
    private String referencia;

    @Column(name = "clave_idempotencia", nullable = false, length = 80)
    private String claveIdempotencia;

    @Column(name = "huella_solicitud", nullable = false, length = 64)
    private String huellaSolicitud;

    @Column(name = "creada_en", nullable = false)
    private LocalDateTime creadaEn;

    protected Operacion() {
    }

    public static Operacion retiroEnCajero(Long cuentaId, Long tarjetaId, String terminalId, BigDecimal monto,
                                           BigDecimal saldoResultante, String claveIdempotencia, String huella,
                                           LocalDateTime creadaEn) {
        Operacion op = base(cuentaId, TIPO_RETIRO, CANAL_CAJERO, monto, saldoResultante, huella, creadaEn);
        op.tarjetaId = tarjetaId;
        op.terminalId = terminalId;
        op.descripcion = "Retiro en cajero " + terminalId;
        op.claveIdempotencia = claveIdempotencia;
        return op;
    }

    /** Un lado de un pago. Clave = pago + tipo: el unique (canal, clave) impide aplicar dos veces el mismo lado. */
    public static Operacion dePago(Long cuentaId, String tipo, BigDecimal monto, BigDecimal saldoResultante,
                                   String pagoId, String descripcion, String huella, LocalDateTime creadaEn) {
        Operacion op = base(cuentaId, tipo, CANAL_PAGOS, monto, saldoResultante, huella, creadaEn);
        op.referencia = pagoId;
        op.descripcion = descripcion;
        op.claveIdempotencia = pagoId + ":" + tipo;
        return op;
    }

    private static Operacion base(Long cuentaId, String tipo, String canal, BigDecimal monto, BigDecimal saldo,
                                  String huella, LocalDateTime creadaEn) {
        Operacion op = new Operacion();
        op.cuentaId = cuentaId;
        op.tipo = tipo;
        op.canal = canal;
        op.monto = monto;
        op.saldoResultante = saldo;
        op.huellaSolicitud = huella;
        op.creadaEn = creadaEn;
        return op;
    }

    /** Los cargos restan saldo: retiro, transferencia enviada y pago de servicio. */
    public boolean esCargo() {
        return TIPO_RETIRO.equals(tipo) || TIPO_TRANSFERENCIA_ENVIADA.equals(tipo) || TIPO_PAGO_SERVICIO.equals(tipo);
    }

    public String getDescripcion() {
        return descripcion;
    }

    public String getReferencia() {
        return referencia;
    }

    public Long getId() {
        return id;
    }

    public Long getCuentaId() {
        return cuentaId;
    }

    public Long getTarjetaId() {
        return tarjetaId;
    }

    public String getTipo() {
        return tipo;
    }

    public String getCanal() {
        return canal;
    }

    public BigDecimal getMonto() {
        return monto;
    }

    public BigDecimal getSaldoResultante() {
        return saldoResultante;
    }

    public String getTerminalId() {
        return terminalId;
    }

    public String getHuellaSolicitud() {
        return huellaSolicitud;
    }

    public LocalDateTime getCreadaEn() {
        return creadaEn;
    }
}
