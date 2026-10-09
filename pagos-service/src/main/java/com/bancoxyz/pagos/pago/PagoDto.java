package com.bancoxyz.pagos.pago;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Respuesta de un pago con su historial (lo que el BFF muestra como comprobante). */
public record PagoDto(String id, String operacion, String estado, String motivo, Long cuentaOrigen, Long cuentaDestino,
                      String convenio, BigDecimal monto, String validacion, String canal, LocalDateTime creadoEn,
                      LocalDateTime actualizadoEn, List<Paso> historial) {

    public record Paso(String evento, String detalle, LocalDateTime ocurridoEn) {
    }

    static PagoDto desde(Pago p, List<PasoDelHistorial> pasos) {
        return new PagoDto(p.getId(), p.getOperacion(), p.getEstado().name(), p.getMotivo(), p.getCuentaOrigen(),
                p.getCuentaDestino(), p.getConvenio(), p.getMonto(), p.getValidacion(), p.getCanal(), p.getCreadoEn(),
                p.getActualizadoEn(), pasos.stream().map(s -> new Paso(s.getEvento(), s.getDetalle(), s.getOcurridoEn())).toList());
    }
}
