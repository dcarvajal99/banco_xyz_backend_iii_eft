package com.bancoxyz.pagos.pago;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Un paso en la vida de un pago (solicitado, rechazado por fraude, completado...). */
@Entity
@Table(schema = "pagos", name = "historial")
public class PasoDelHistorial {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pago_id", nullable = false, length = 36)
    private String pagoId;

    @Column(nullable = false, length = 40)
    private String evento;

    @Column(length = 120)
    private String detalle;

    @Column(name = "ocurrido_en", nullable = false)
    private LocalDateTime ocurridoEn;

    protected PasoDelHistorial() {
    }

    public PasoDelHistorial(String pagoId, String evento, String detalle, LocalDateTime ocurridoEn) {
        this.pagoId = pagoId;
        this.evento = evento;
        this.detalle = detalle == null || detalle.length() <= 120 ? detalle : detalle.substring(0, 120);
        this.ocurridoEn = ocurridoEn;
    }

    public String getEvento() {
        return evento;
    }

    public String getDetalle() {
        return detalle;
    }

    public LocalDateTime getOcurridoEn() {
        return ocurridoEn;
    }
}
