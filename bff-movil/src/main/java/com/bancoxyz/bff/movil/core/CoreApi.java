package com.bancoxyz.bff.movil.core;

import java.util.List;

/**
 * Lo que el BFF movil necesita del core. La implementan el cliente HTTP real y el proxy de cache, de modo que
 * los controladores no saben (ni les importa) si la respuesta vino de la red o de la cache.
 */
public interface CoreApi {

    UsuarioAutenticadoCore autenticar(String usuario, String password, String correlacion);

    List<CuentaCore> cuentasDeCliente(long clienteId, long usuarioId, String correlacion);

    PaginaMovimientosCore movimientos(long cuentaId, long usuarioId, int pagina, int tamano, String correlacion);
}
