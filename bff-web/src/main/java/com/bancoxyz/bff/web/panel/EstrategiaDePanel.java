package com.bancoxyz.bff.web.panel;

import com.bancoxyz.bff.web.seguridad.UsuarioWeb;

/**
 * Strategy: cada audiencia del navegador tiene su forma de armar el panel.
 *
 * <p>En la estrategia elegida (un backend por tipo de cliente) el tipo de frontend lo fija el despliegue: a este BFF
 * solo llegan navegadores. Pero dentro del navegador conviven dos audiencias, la banca del cliente y la consola del
 * ejecutivo. Strategy evita un {@code if (rol)} en el controlador, que seria el mismo {@code if (tipoDeCliente)} del
 * "caso monolito" de la guia solo que movido de lugar: agregar una audiencia nueva es agregar una clase.</p>
 */
public interface EstrategiaDePanel {

    boolean atiende(UsuarioWeb usuario);

    Object construir(UsuarioWeb usuario, String correlacion);
}
