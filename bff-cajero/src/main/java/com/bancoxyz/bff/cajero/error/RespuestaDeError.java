package com.bancoxyz.bff.cajero.error;

/**
 * Error en el formato del cajero: un codigo para el software del terminal, un texto para la pantalla y
 * una accion para el hardware (devolver o retener la tarjeta, reintentar, volver al menu).
 */
public record RespuestaDeError(String codigo, String mensajePantalla, String accion, Integer intentosRestantes) {
}
