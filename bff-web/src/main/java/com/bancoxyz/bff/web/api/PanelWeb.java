package com.bancoxyz.bff.web.api;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Panel del cliente en UNA respuesta: ficha, cuentas completas con totales, pagos recientes y avisos (cuatro llamadas
 * a tres microservicios que el navegador no hace). {@code noDisponibles} lista las secciones que no se pudieron traer:
 * si clientes-service o pagos-service fallan el panel se muestra igual, sin esa seccion.
 */
public record PanelWeb(String usuario, JsonNode cliente, JsonNode cuentas, BigDecimal disponible, BigDecimal deuda,
                       JsonNode pagosRecientes, JsonNode avisos, List<String> noDisponibles) {
}
