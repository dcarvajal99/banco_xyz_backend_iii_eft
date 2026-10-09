package com.bancoxyz.bff.web.adaptador;

import java.util.List;

/** Pagina con todos los metadatos que necesita un paginador de tabla. */
public record PaginaWeb(List<MovimientoWeb> contenido, int pagina, int tamano, long totalElementos, int totalPaginas,
                        boolean primera, boolean ultima) {
}
