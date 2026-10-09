package com.bancoxyz.bff.movil.movimientos;

import java.util.List;

import com.bancoxyz.bff.movil.adaptador.MovimientoMovil;

/** @param siguiente cursor de la siguiente pagina; ausente en la ultima (no se serializan nulos) */
public record PaginaMovil(List<MovimientoMovil> items, String siguiente) {
}
