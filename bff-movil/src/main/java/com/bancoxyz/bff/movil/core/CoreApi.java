package com.bancoxyz.bff.movil.core;

import java.math.BigDecimal;
import java.util.List;

/** Lo que la app necesita de los microservicios (cuentas-service y pagos-service). */
public interface CoreApi {

    List<CuentaCore> cuentasDeCliente(long clienteId, long usuarioId, String correlacion);

    PaginaMovimientosCore movimientos(long cuentaId, long usuarioId, int pagina, int tamano, String correlacion);

    PagoCore transferir(long origen, long destino, BigDecimal monto, String claveIdempotencia, String correlacion);

    PagoCore pago(String id, String correlacion);
}
