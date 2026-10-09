package com.bancoxyz.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Maestro de clientes que publica la migracion, con un identificador <b>estable</b>.
 *
 * <p>El archivo legacy no trae id de cliente: solo el nombre del titular en cada cuenta. Si cada
 * microservicio derivara su propio id (por ejemplo, con una columna identity al copiar), clientes-service
 * y cuentas-service numerarian distinto y una cuenta apuntaria a otra persona. Por eso el id lo asigna el
 * batch, una sola vez por titular, y no cambia en las corridas siguientes: los dos servicios lo copian tal
 * cual y la referencia {@code cuenta.cliente_id} queda consistente sin que tengan que llamarse entre si.</p>
 *
 * <p>Las filas nunca se borran, tampoco al reanudar una corrida: borrar un cliente y volver a crearlo le
 * daria otro id.</p>
 */
@Entity
@Table(name = "cliente_migrado", uniqueConstraints = @UniqueConstraint(name = "uk_cliente_migrado_nombre",
        columnNames = "nombre"))
public class ClienteMigrado {

    /** Lo asigna {@code PublicacionDelCierreTasklet} (siguiente al mayor existente), no la base. */
    @Id
    private Long id;

    @Column(nullable = false, length = 120)
    private String nombre;

    /** Edad del titular en la ultima publicacion en que aparecio. */
    @Column(nullable = false)
    private int edad;

    @Column(name = "cuentas_en_cierre", nullable = false)
    private int cuentasEnCierre;

    @Column(name = "primera_ejecucion", nullable = false)
    private Long primeraEjecucion;

    @Column(name = "ultima_ejecucion", nullable = false)
    private Long ultimaEjecucion;

    @Column(name = "actualizado_en", nullable = false)
    private LocalDateTime actualizadoEn;

    public ClienteMigrado() {
        // Constructor requerido por JPA.
    }

    public ClienteMigrado(Long id, String nombre, Long jobExecutionId) {
        this.id = id;
        this.nombre = nombre;
        this.primeraEjecucion = jobExecutionId;
        this.ultimaEjecucion = jobExecutionId;
        this.actualizadoEn = LocalDateTime.now();
    }

    /** Lo deja al dia con la publicacion actual. */
    public void publicar(int edad, int cuentasEnCierre, Long jobExecutionId) {
        this.edad = edad;
        this.cuentasEnCierre = cuentasEnCierre;
        this.ultimaEjecucion = jobExecutionId;
        this.actualizadoEn = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getNombre() {
        return nombre;
    }

    public int getEdad() {
        return edad;
    }

    public int getCuentasEnCierre() {
        return cuentasEnCierre;
    }

    public Long getPrimeraEjecucion() {
        return primeraEjecucion;
    }

    public Long getUltimaEjecucion() {
        return ultimaEjecucion;
    }

    public LocalDateTime getActualizadoEn() {
        return actualizadoEn;
    }

    @Override
    public boolean equals(Object otro) {
        return this == otro || otro instanceof ClienteMigrado cliente && Objects.equals(nombre, cliente.nombre);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(nombre);
    }

    @Override
    public String toString() {
        return "ClienteMigrado{id=" + id + ", nombre='" + nombre + "'}";
    }
}
