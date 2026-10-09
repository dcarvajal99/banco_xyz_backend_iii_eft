package com.bancoxyz.clientes;

import static org.assertj.core.api.Assertions.assertThat;

import com.bancoxyz.clientes.sincronizacion.ResumenSincronizacion;
import com.bancoxyz.clientes.sincronizacion.SincronizadorDeClientes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Base nueva donde el batch aun no corrio: el servicio arranca igual y lo avisa (otra base H2, sin el esquema migracion). */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:sin-migracion;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("prueba")
class SinMigracionTest {

    @Autowired
    private SincronizadorDeClientes sincronizador;

    @Test
    @DisplayName("Sin migracion.cliente_migrado la sincronizacion no falla: informa que no hay maestro")
    void sinMaestro() {
        ResumenSincronizacion resumen = sincronizador.sincronizar();
        assertThat(resumen.conMigracion()).isFalse();
        assertThat(resumen.clientesNuevos()).isZero();
    }
}
