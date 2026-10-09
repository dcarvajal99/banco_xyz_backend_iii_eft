package com.bancoxyz.bff.movil.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.bancoxyz.bff.movil.core.CoreApi;
import com.bancoxyz.bff.movil.core.CuentaCore;
import com.bancoxyz.bff.movil.core.PaginaMovimientosCore;
import com.bancoxyz.bff.movil.core.UsuarioAutenticadoCore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProxyCoreConCacheTest {

    private final AtomicInteger llamadasDeCuentas = new AtomicInteger();
    private final AtomicInteger llamadasDeMovimientos = new AtomicInteger();
    private Instant ahora = Instant.parse("2026-09-12T20:00:00Z");

    private final CoreApi coreSimulado = new CoreApi() {
        @Override
        public UsuarioAutenticadoCore autenticar(String usuario, String password, String correlacion) {
            return new UsuarioAutenticadoCore(3, 6L, usuario, "Jane Smith", "CLIENTE");
        }

        @Override
        public List<CuentaCore> cuentasDeCliente(long clienteId, long usuarioId, String correlacion) {
            llamadasDeCuentas.incrementAndGet();
            return List.of(new CuentaCore(101, clienteId, "Jane Smith", "ahorro", BigDecimal.TEN, BigDecimal.TEN,
                    BigDecimal.ZERO, BigDecimal.ZERO, false, null, 1, null));
        }

        @Override
        public PaginaMovimientosCore movimientos(long cuentaId, long usuarioId, int pagina, int tamano, String correlacion) {
            llamadasDeMovimientos.incrementAndGet();
            return new PaginaMovimientosCore(List.of(), pagina, tamano, 0, 0);
        }
    };

    private final Clock reloj = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return ahora;
        }
    };

    @Test
    @DisplayName("Dentro de 10 s las cuentas salen de la cache; vencida o de otro usuario, se piden al core")
    void cacheConVencimiento() {
        ProxyCoreConCache proxy = new ProxyCoreConCache(coreSimulado, reloj, 10);
        proxy.cuentasDeCliente(6, 3, "c1");
        proxy.cuentasDeCliente(6, 3, "c2");
        assertThat(llamadasDeCuentas).hasValue(1);

        proxy.cuentasDeCliente(6, 99, "c3");
        assertThat(llamadasDeCuentas).hasValue(2);

        ahora = ahora.plusSeconds(11);
        proxy.cuentasDeCliente(6, 3, "c4");
        assertThat(llamadasDeCuentas).hasValue(3);

        proxy.vaciar();
        proxy.cuentasDeCliente(6, 3, "c5");
        assertThat(llamadasDeCuentas).hasValue(4);
    }

    @Test
    @DisplayName("Autenticar no se cachea; las paginas de movimientos si, por usuario, cuenta, pagina y tamano")
    void movimientosEnCache() {
        ProxyCoreConCache proxy = new ProxyCoreConCache(coreSimulado, reloj, 10);
        assertThat(proxy.autenticar("jane.smith", "x", "c").nombre()).isEqualTo("Jane Smith");
        assertThat(proxy.movimientos(101, 3, 2, 5, "c").pagina()).isEqualTo(2);
        proxy.movimientos(101, 3, 2, 5, "c");
        assertThat(llamadasDeMovimientos).hasValue(1);

        proxy.movimientos(101, 3, 3, 5, "c");
        proxy.movimientos(101, 99, 2, 5, "c");
        proxy.movimientos(117, 3, 2, 5, "c");
        proxy.movimientos(101, 3, 2, 10, "c");
        assertThat(llamadasDeMovimientos).hasValue(5);
    }

    @Test
    @DisplayName("Las entradas vencidas se desalojan al escribir: el mapa no crece con cada usuario que abrio la app")
    void desalojoDeVencidas() {
        ProxyCoreConCache proxy = new ProxyCoreConCache(coreSimulado, reloj, 10);
        for (int usuario = 1; usuario <= 50; usuario++) {
            proxy.cuentasDeCliente(6, usuario, "c");
        }
        assertThat(proxy.entradas()).isEqualTo(50);

        ahora = ahora.plusSeconds(11);
        proxy.cuentasDeCliente(6, 1, "c");
        assertThat(proxy.entradas()).isEqualTo(1);
    }
}
