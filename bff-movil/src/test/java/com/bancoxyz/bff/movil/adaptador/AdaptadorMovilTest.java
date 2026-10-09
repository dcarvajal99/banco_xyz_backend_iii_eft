package com.bancoxyz.bff.movil.adaptador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;

import com.bancoxyz.bff.movil.core.CuentaCore;
import com.bancoxyz.bff.movil.core.MovimientoCore;
import com.bancoxyz.bff.movil.error.ErrorMovil;
import com.bancoxyz.bff.movil.movimientos.Cursor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AdaptadorMovilTest {

    private final AdaptadorMovil adaptador = new AdaptadorMovil();

    @Test
    @DisplayName("Disponible suma solo ahorro y deuda solo prestamo e hipoteca")
    void disponibleYDeuda() {
        List<CuentaCore> cuentas = List.of(cuenta(1, "ahorro", "1000"), cuenta(2, "ahorro", "500"),
                cuenta(3, "prestamo", "7000"), cuenta(4, "hipoteca", "3000"), cuenta(5, "otro", "99"));
        assertThat(adaptador.disponible(cuentas)).isEqualByComparingTo("1500");
        assertThat(adaptador.deuda(cuentas)).isEqualByComparingTo("10000");
        assertThat(adaptador.disponible(List.of())).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("Ultimos movimientos: los mas recientes primero, recortados a 24 caracteres")
    void ultimos() {
        List<MovimientoMovil> ultimos = adaptador.ultimos(List.of(
                movimiento("C-1", "2024-01-01", "Viejo"),
                movimiento("C-2", "2024-12-31", "Una descripcion bastante mas larga que veinticuatro"),
                movimiento("C-3", "2024-06-01", null)), 2);
        assertThat(ultimos).hasSize(2);
        assertThat(ultimos.get(0).fecha()).isEqualTo(LocalDate.of(2024, 12, 31));
        assertThat(ultimos.get(0).desc()).hasSize(24).endsWith("…");
        assertThat(ultimos.get(1).desc()).isNull();
    }

    @Test
    @DisplayName("Primer nombre y cursor opaco")
    void nombreYCursor() {
        assertThat(AdaptadorMovil.primerNombre("Jane Smith")).isEqualTo("Jane");
        assertThat(AdaptadorMovil.primerNombre(" ")).isEmpty();
        assertThat(AdaptadorMovil.primerNombre(null)).isEmpty();
        Cursor cursor = new Cursor(3, 10);
        assertThat(Cursor.decodificar(cursor.codificar())).isEqualTo(cursor);
        String fueraDeRango = Base64.getUrlEncoder().encodeToString("0:50".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> Cursor.decodificar(fueraDeRango)).isInstanceOf(ErrorMovil.class);
        assertThatThrownBy(() -> Cursor.decodificar("%%%")).isInstanceOf(ErrorMovil.class);
    }

    private static CuentaCore cuenta(long id, String tipo, String saldo) {
        return new CuentaCore(id, 1L, "X", tipo, new BigDecimal(saldo), new BigDecimal(saldo), BigDecimal.ZERO, BigDecimal.ZERO,
                false, null, 1, null);
    }

    private static MovimientoCore movimiento(String id, String fecha, String descripcion) {
        return new MovimientoCore(id, 1, LocalDate.parse(fecha), "deposito", BigDecimal.ONE, descripcion, "CIERRE", false, null);
    }
}
