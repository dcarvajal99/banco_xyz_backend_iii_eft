package com.bancoxyz.pagos.config;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Propiedades de pagos-service bajo {@code banco.pagos}.
 *
 * @param zonaHoraria       zona del banco
 * @param cuentasUrl        base de la API de cuentas-service ({@code https://cuentas-service/api/v1} por Eureka)
 * @param relevosPermitidos CN de los BFF que pueden reenviar el token de un usuario
 * @param fraude            reglas de fraude (las entrega el Config Server)
 */
@ConfigurationProperties(prefix = "banco.pagos")
public record PropiedadesPagos(String zonaHoraria, String cuentasUrl, List<String> relevosPermitidos, Fraude fraude) {

    /**
     * @param montoMaximo          sobre este monto la operacion se rechaza y se emite una alerta
     * @param cuentasEnObservacion cuentas marcadas por el area de riesgo (origen o destino)
     */
    public record Fraude(BigDecimal montoMaximo, Set<Long> cuentasEnObservacion) {
    }
}
