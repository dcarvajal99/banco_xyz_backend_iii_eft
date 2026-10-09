package com.bancoxyz.bff.cajero.adaptador;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

import com.bancoxyz.bff.cajero.core.CuentaCore;
import com.bancoxyz.bff.cajero.core.OperacionCore;
import com.bancoxyz.bff.cajero.error.Acciones;
import com.bancoxyz.bff.cajero.operacion.ComprobanteRetiro;
import com.bancoxyz.bff.cajero.operacion.PantallaSaldo;
import org.springframework.stereotype.Component;

/**
 * Adapter: convierte la respuesta completa y neutra del core en lo que muestra una pantalla de cajero.
 *
 * <p>De los doce campos de una cuenta, el cajero usa uno. El resto (tasas, intereses, anomalias, titular
 * completo) no sale del banco hacia un aparato en la calle.</p>
 */
@Component
public class AdaptadorCajero {

    private final Clock reloj;

    public AdaptadorCajero(Clock reloj) {
        this.reloj = reloj;
    }

    public PantallaSaldo saldo(CuentaCore cuenta) {
        return new PantallaSaldo(cuenta.saldoDisponible(), "CLP",
                "Su saldo disponible es " + pesos(cuenta.saldoDisponible()),
                LocalDateTime.now(reloj).truncatedTo(ChronoUnit.SECONDS));
    }

    public ComprobanteRetiro comprobante(OperacionCore operacion) {
        return new ComprobanteRetiro("OP-%06d".formatted(operacion.operacionId()), operacion.monto(),
                operacion.saldoResultante(), operacion.fechaHora(), cuentaEnmascarada(operacion.cuentaId()),
                Acciones.DISPENSAR);
    }

    /** $12.060: separador de miles con punto, sin decimales, como se muestran los pesos chilenos. */
    public static String pesos(BigDecimal monto) {
        DecimalFormatSymbols simbolos = new DecimalFormatSymbols(Locale.ROOT);
        simbolos.setGroupingSeparator('.');
        DecimalFormat formato = new DecimalFormat("#,##0", simbolos);
        return "$" + formato.format(monto.setScale(0, RoundingMode.DOWN));
    }

    public static String cuentaEnmascarada(long cuentaId) {
        return "****" + "%04d".formatted(cuentaId % 10000);
    }

    /** "Diana Prince" -> "Diana P.": se saluda al cliente sin exponer el nombre completo en la pantalla. */
    public static String titularAbreviado(String titular) {
        if (titular == null || titular.isBlank()) {
            return "";
        }
        String[] partes = titular.trim().split("\\s+");
        return partes.length == 1 ? partes[0] : partes[0] + " " + partes[1].charAt(0) + ".";
    }
}
