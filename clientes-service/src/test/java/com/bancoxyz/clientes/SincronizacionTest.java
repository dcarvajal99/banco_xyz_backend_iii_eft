package com.bancoxyz.clientes;

import static org.assertj.core.api.Assertions.assertThat;

import com.bancoxyz.clientes.sincronizacion.ResumenSincronizacion;
import com.bancoxyz.clientes.sincronizacion.SincronizadorDeClientes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Copia del maestro de clientes que publico el batch. */
class SincronizacionTest extends PruebaDeClientes {

    @Test
    @DisplayName("Los clientes quedan con el MISMO id que la migracion: cuentas-service copia el mismo id en sus cuentas")
    void mismosIds() {
        assertThat(jdbc.sql("select id, nombre from clientes.cliente order by id").query().listOfRows())
                .extracting(f -> f.get("id") + "=" + f.get("nombre"))
                .containsExactly("1=Alice Brown", "2=Bob Johnson", "3=Charlie Green", "4=Diana Prince", "5=Jane Smith",
                        "6=John Doe", "7=Steve Rogers");
        assertThat(jdbc.sql("select count(*) from clientes.usuario where rol = 'CLIENTE'").query(Long.class).single()).isEqualTo(7);
        assertThat(jdbc.sql("select count(*) from clientes.usuario where usuario = 'ejecutivo'").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("Volver a sincronizar no duplica ni pisa un perfil que el cliente ya edito")
    void idempotente() {
        jdbc.sql("update clientes.cliente set email = 'diana@correo.cl' where id = 4").update();

        ResumenSincronizacion segunda = sincronizador.sincronizar();

        assertThat(segunda.clientesNuevos()).isZero();
        assertThat(segunda.usuariosNuevos()).isZero();
        assertThat(segunda.clientesEnMigracion()).isEqualTo(7);
        assertThat(jdbc.sql("select email from clientes.cliente where id = 4").query(String.class).single())
                .isEqualTo("diana@correo.cl");
    }

    @Test
    @DisplayName("Nombre de usuario de demostracion: minusculas, sin tildes, con punto")
    void nombreDeUsuario() {
        assertThat(SincronizadorDeClientes.nombreDeUsuario(" María José  Pérez ")).isEqualTo("maria.jose.perez");
    }
}
