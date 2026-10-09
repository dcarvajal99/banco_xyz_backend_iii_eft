package com.bancoxyz.bff.movil.core;

import java.util.List;

/** DTO propio del contrato pagina-movimientos.json. */
public record PaginaMovimientosCore(List<MovimientoCore> contenido, int pagina, int tamano, long totalElementos,
                                    int totalPaginas) {
}
