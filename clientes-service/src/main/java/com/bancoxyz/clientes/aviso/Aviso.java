package com.bancoxyz.clientes.aviso;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Aviso al cliente generado desde un evento de Kafka (transaccion o alerta de seguridad). */
@Entity
@Table(schema = "clientes", name = "notificacion")
public class Aviso {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cliente_id", nullable = false)
    private long clienteId;

    @Column(name = "evento_id", nullable = false, length = 36)
    private String eventoId;

    @Column(nullable = false, length = 40)
    private String tipo;

    @Column(nullable = false, length = 80)
    private String titulo;

    @Column(nullable = false, length = 200)
    private String mensaje;

    @Column(nullable = false)
    private boolean seguridad;

    @Column(name = "creada_en", nullable = false)
    private LocalDateTime creadaEn;

    protected Aviso() {
    }

    public Aviso(long clienteId, String eventoId, String tipo, String titulo, String mensaje, boolean seguridad,
                 LocalDateTime creadaEn) {
        this.clienteId = clienteId;
        this.eventoId = eventoId;
        this.tipo = tipo;
        this.titulo = titulo;
        this.mensaje = mensaje.length() <= 200 ? mensaje : mensaje.substring(0, 200);
        this.seguridad = seguridad;
        this.creadaEn = creadaEn;
    }

    public Long getId() {
        return id;
    }

    public long getClienteId() {
        return clienteId;
    }

    public String getTipo() {
        return tipo;
    }

    public String getTitulo() {
        return titulo;
    }

    public String getMensaje() {
        return mensaje;
    }

    public boolean isSeguridad() {
        return seguridad;
    }

    public LocalDateTime getCreadaEn() {
        return creadaEn;
    }
}
