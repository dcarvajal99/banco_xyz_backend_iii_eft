package com.bancoxyz.cuentas.cuenta;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** La cuenta completa, sin recortes: cada BFF elige que mostrar a su canal. */
public record CuentaDto(long cuentaId, Long clienteId, String titular, String tipo, String alias, String estado,
                        String origen, BigDecimal saldoDisponible, BigDecimal saldoCierre, BigDecimal tasaMensual,
                        BigDecimal interesCierre, boolean anomalia, String observacion, Long cierreJobExecutionId,
                        LocalDateTime abiertaEn, LocalDateTime cerradaEn, LocalDateTime actualizadaEn) {

    public static CuentaDto desde(Cuenta c) {
        return new CuentaDto(c.getId(), c.getClienteId(), c.getTitular(), c.getTipo(), c.getAlias(), c.getEstado(),
                c.getOrigen(), c.getSaldoDisponible(), c.getSaldoCierre(), c.getTasaMensual(), c.getInteresCierre(),
                c.isAnomalia(), c.getObservacion(), c.getCierreJobExecutionId(), c.getAbiertaEn(), c.getCerradaEn(),
                c.getActualizadaEn());
    }
}
