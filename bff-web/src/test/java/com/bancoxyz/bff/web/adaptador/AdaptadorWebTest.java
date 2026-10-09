package com.bancoxyz.bff.web.adaptador;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.bancoxyz.bff.web.core.CalidadCore;
import com.bancoxyz.bff.web.core.CierreCore;
import com.bancoxyz.bff.web.core.RechazadoCore;
import com.bancoxyz.bff.web.core.ResumenDiarioCore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AdaptadorWebTest {

    private final AdaptadorWeb adaptador = new AdaptadorWeb();

    @Test
    @DisplayName("Tasa como porcentaje con coma decimal")
    void porcentaje() {
        assertThat(AdaptadorWeb.porcentaje(new BigDecimal("0.00500"))).isEqualTo("0,50 %");
        assertThat(AdaptadorWeb.porcentaje(new BigDecimal("0.015"))).isEqualTo("1,50 %");
        assertThat(AdaptadorWeb.porcentaje(null)).isNull();
    }

    @Test
    @DisplayName("Serie del resumen diario en columnas paralelas")
    void serie() {
        ResumenDiarioWeb resumen = adaptador.resumenDiario(List.of(
                new ResumenDiarioCore(LocalDate.of(2024, 6, 29), 3, new BigDecimal("100"), new BigDecimal("300"), BigDecimal.TEN, 1, 1),
                new ResumenDiarioCore(LocalDate.of(2024, 6, 30), 2, new BigDecimal("200"), new BigDecimal("400"), BigDecimal.TEN, 0, 1)));
        assertThat(resumen.serie().fechas()).containsExactly(LocalDate.of(2024, 6, 29), LocalDate.of(2024, 6, 30));
        assertThat(resumen.serie().creditos()).containsExactly(new BigDecimal("300"), new BigDecimal("400"));
        assertThat(resumen.serie().anomalias()).containsExactly(1L, 0L);
    }

    @Test
    @DisplayName("Cierre principal: el de intereses, si no el primero, y null si no hay cierres")
    void cierrePrincipal() {
        CierreCore movimientos = new CierreCore("MOVIMIENTOS", 1, "j", "COMPLETED", "ACEPTABLE", LocalDateTime.now());
        CierreCore intereses = new CierreCore("INTERESES", 2, "j", "COMPLETED", "DEGRADADA", LocalDateTime.now());
        assertThat(adaptador.cierrePrincipal(List.of(movimientos, intereses)).conjunto()).isEqualTo("INTERESES");
        assertThat(adaptador.cierrePrincipal(List.of(movimientos)).aviso()).isNull();
        assertThat(adaptador.cierrePrincipal(List.of())).isNull();
    }

    @Test
    @DisplayName("Calidad sin cierres degradados no avisa y las alertas listan rechazados")
    void calidadYAlertas() {
        CalidadCore calidad = new CalidadCore(List.of(new CierreCore("INTERESES", 1, "j", "COMPLETED", "ACEPTABLE", null)),
                List.of(new RechazadoCore("calculoInteresesMensualesJob", "FILTRADO", 9)));
        assertThat(adaptador.calidad(calidad).aviso()).isNull();
        assertThat(adaptador.alertas(calidad)).containsExactly("calculoInteresesMensualesJob: 9 filas FILTRADO.");
    }
}
