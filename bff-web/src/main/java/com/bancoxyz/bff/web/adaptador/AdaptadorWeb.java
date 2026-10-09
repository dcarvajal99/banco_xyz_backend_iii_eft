package com.bancoxyz.bff.web.adaptador;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.bancoxyz.bff.web.core.CalidadCore;
import com.bancoxyz.bff.web.core.CierreCore;
import com.bancoxyz.bff.web.core.ClienteDelBancoCore;
import com.bancoxyz.bff.web.core.CuentaCore;
import com.bancoxyz.bff.web.core.EstadoAnualCore;
import com.bancoxyz.bff.web.core.MovimientoCore;
import com.bancoxyz.bff.web.core.PaginaMovimientosCore;
import com.bancoxyz.bff.web.core.RechazadoCore;
import com.bancoxyz.bff.web.core.ResumenDiarioCore;
import org.springframework.stereotype.Component;

/**
 * Adapter: de las respuestas neutras del core a las vistas de la web.
 *
 * <p>La web recibe TODO el dato (tasas, intereses, anomalias y observaciones del batch) y ademas lo que una pantalla
 * compleja necesita y el core no tiene por que saber: etiquetas legibles, la tasa como porcentaje, totales por tipo,
 * metadatos de paginacion y series para graficos.</p>
 */
@Component
public class AdaptadorWeb {

    static final Map<String, String> TIPOS_DE_CUENTA = Map.of(
            "ahorro", "Cuenta de ahorro", "prestamo", "Préstamo", "hipoteca", "Crédito hipotecario");
    static final Map<String, String> TIPOS_DE_MOVIMIENTO = Map.of(
            "deposito", "Depósito", "retiro", "Retiro", "compra", "Compra", "pago", "Pago");
    static final String CALIDAD_DEGRADADA = "DEGRADADA";

    public CuentaWeb cuenta(CuentaCore c) {
        return new CuentaWeb(c.cuentaId(), c.tipo(), TIPOS_DE_CUENTA.getOrDefault(c.tipo(), c.tipo()), c.saldoDisponible(),
                c.saldoCierre(), c.tasaMensual(), porcentaje(c.tasaMensual()), c.interesCierre(), c.anomalia(), c.observacion());
    }

    public MovimientoWeb movimiento(MovimientoCore m) {
        return new MovimientoWeb(m.id(), m.cuentaId(), m.fecha(), m.tipo(), TIPOS_DE_MOVIMIENTO.getOrDefault(m.tipo(), m.tipo()),
                m.monto(), m.descripcion(), m.origen(), m.anomalia(), m.observacion());
    }

    public EstadoAnualWeb estado(EstadoAnualCore e) {
        return new EstadoAnualWeb(e.cuentaId(), e.anio(), e.cantidadMovimientos(), e.totalDepositos(), e.totalCargos(),
                e.saldoNeto(), e.primeraFecha(), e.ultimaFecha(), e.movimientosConAnomalia());
    }

    public PaginaWeb pagina(PaginaMovimientosCore p) {
        return new PaginaWeb(p.contenido().stream().map(this::movimiento).toList(), p.pagina(), p.tamano(),
                p.totalElementos(), p.totalPaginas(), p.pagina() == 0, p.pagina() + 1 >= p.totalPaginas());
    }

    /** Los mas recientes de todas las cuentas juntas, que es lo que el core no puede entregar en una sola llamada. */
    public List<MovimientoWeb> recientes(List<MovimientoCore> movimientos, int cantidad) {
        return movimientos.stream()
                .sorted(Comparator.comparing(MovimientoCore::fecha).reversed()
                        .thenComparing(MovimientoCore::id, Comparator.reverseOrder()))
                .limit(cantidad)
                .map(this::movimiento)
                .toList();
    }

    public ResumenWeb resumen(List<CuentaCore> cuentas) {
        BigDecimal ahorro = sumar(cuentas, List.of("ahorro"));
        BigDecimal deuda = sumar(cuentas, List.of("prestamo", "hipoteca"));
        return new ResumenWeb(ahorro, deuda, cuentas.size(), cuentas.stream().filter(CuentaCore::anomalia).count());
    }

    public CierreWeb cierre(CierreCore c) {
        String aviso = CALIDAD_DEGRADADA.equals(c.calidad())
                ? "Los datos de " + c.conjunto().toLowerCase() + " se publicaron con calidad degradada: parte de los "
                + "registros del sistema legacy quedó en revisión."
                : null;
        return new CierreWeb(c.conjunto(), c.calidad(), c.jobExecutionId(), c.finalizadoEn(), aviso);
    }

    /** El cierre que importa para el panel del cliente: el de intereses, que es de donde salen sus saldos. */
    public CierreWeb cierrePrincipal(List<CierreCore> cierres) {
        return cierres.stream().filter(c -> "INTERESES".equals(c.conjunto())).findFirst()
                .or(() -> cierres.stream().findFirst())
                .map(this::cierre)
                .orElse(null);
    }

    public ResumenDiarioWeb resumenDiario(List<ResumenDiarioCore> filas) {
        List<FilaResumenWeb> tabla = filas.stream().map(this::fila).toList();
        SerieWeb serie = new SerieWeb(
                filas.stream().map(ResumenDiarioCore::fecha).toList(),
                filas.stream().map(ResumenDiarioCore::totalDebitos).toList(),
                filas.stream().map(ResumenDiarioCore::totalCreditos).toList(),
                filas.stream().map(ResumenDiarioCore::cantidadAnomalias).toList());
        return new ResumenDiarioWeb(tabla, serie);
    }

    public FilaResumenWeb fila(ResumenDiarioCore r) {
        return new FilaResumenWeb(r.fecha(), r.cantidadTransacciones(), r.totalDebitos(), r.totalCreditos(),
                r.montoMaximo(), r.cantidadAnomalias());
    }

    public CalidadWeb calidad(CalidadCore c) {
        List<CierreWeb> cierres = c.cierres().stream().map(this::cierre).toList();
        long total = c.rechazados().stream().mapToLong(RechazadoCore::cantidad).sum();
        String aviso = cierres.stream().anyMatch(x -> CALIDAD_DEGRADADA.equals(x.calidad()))
                ? "Hay cierres publicados con calidad degradada: revise la cuarentena del batch." : null;
        return new CalidadWeb(cierres, c.rechazados(), total, aviso);
    }

    public List<String> alertas(CalidadCore c) {
        List<String> alertas = new ArrayList<>();
        c.cierres().stream().filter(x -> CALIDAD_DEGRADADA.equals(x.calidad()))
                .forEach(x -> alertas.add("El cierre de %s (ejecución %d, %s) se publicó con calidad DEGRADADA."
                        .formatted(x.conjunto(), x.jobExecutionId(), x.jobNombre())));
        c.rechazados().forEach(r -> alertas.add("%s: %d filas %s.".formatted(r.jobNombre(), r.cantidad(), r.clasificacion())));
        return alertas;
    }

    public ClienteWeb cliente(ClienteDelBancoCore c) {
        return new ClienteWeb(c.clienteId(), c.nombre(), c.usuario(), c.cantidadCuentas());
    }

    /** 0.00500 -> "0,50 %". */
    static String porcentaje(BigDecimal tasa) {
        if (tasa == null) {
            return null;
        }
        return tasa.movePointRight(2).setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',') + " %";
    }

    private static BigDecimal sumar(List<CuentaCore> cuentas, List<String> tipos) {
        return cuentas.stream().filter(c -> tipos.contains(c.tipo())).map(CuentaCore::saldoDisponible)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
