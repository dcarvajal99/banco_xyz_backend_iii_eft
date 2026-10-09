package com.bancoxyz.clientes.cliente;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Cliente del banco: su informacion personal y su perfil de contacto.
 *
 * <p>El legacy solo traia el nombre (no hay RUT): los clientes migrados llegan con el id que les asigno el batch, y el
 * perfil (correo, telefono, direccion) lo completa el propio cliente o un ejecutivo despues. {@link Version} evita que
 * dos ediciones simultaneas del perfil se pisen en silencio.</p>
 */
@Entity
@Table(schema = "clientes", name = "cliente")
public class Cliente {

    public static final String MIGRACION = "MIGRACION";
    public static final String ALTA = "ALTA";

    @Id
    private Long id;

    @Column(nullable = false, length = 120)
    private String nombre;

    private Integer edad;

    @Column(length = 120)
    private String email;

    @Column(length = 20)
    private String telefono;

    @Column(length = 200)
    private String direccion;

    @Column(nullable = false, length = 12)
    private String origen;

    @Column(name = "creado_en", nullable = false)
    private LocalDateTime creadoEn;

    @Column(name = "actualizado_en", nullable = false)
    private LocalDateTime actualizadoEn;

    @Version
    private long version;

    protected Cliente() {
    }

    /** Alta en linea, con el id de la secuencia de altas. */
    public static Cliente alta(long id, String nombre, Integer edad, Perfil perfil, LocalDateTime ahora) {
        Cliente cliente = new Cliente();
        cliente.id = id;
        cliente.nombre = nombre;
        cliente.edad = edad;
        cliente.origen = ALTA;
        cliente.creadoEn = ahora;
        cliente.actualizarPerfil(perfil, ahora);
        return cliente;
    }

    public void actualizarPerfil(Perfil perfil, LocalDateTime ahora) {
        this.email = perfil.email();
        this.telefono = perfil.telefono();
        this.direccion = perfil.direccion();
        this.actualizadoEn = ahora;
    }

    /** Datos de contacto que el cliente puede mantener. */
    public record Perfil(String email, String telefono, String direccion) {
    }

    public Long getId() {
        return id;
    }

    public String getNombre() {
        return nombre;
    }

    public Integer getEdad() {
        return edad;
    }

    public String getEmail() {
        return email;
    }

    public String getTelefono() {
        return telefono;
    }

    public String getDireccion() {
        return direccion;
    }

    public String getOrigen() {
        return origen;
    }

    public LocalDateTime getCreadoEn() {
        return creadoEn;
    }

    public LocalDateTime getActualizadoEn() {
        return actualizadoEn;
    }

    public long getVersion() {
        return version;
    }
}
