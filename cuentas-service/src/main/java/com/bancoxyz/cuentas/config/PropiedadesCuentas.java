package com.bancoxyz.cuentas.config;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Propiedades de cuentas-service bajo {@code banco.cuentas}.
 *
 * @param zonaHoraria          zona con la que se calcula "hoy" para el limite diario
 * @param sincronizarAlIniciar abrir las cuentas del cierre publicado al arrancar
 * @param seguridad            pepper de tarjetas, costo de BCrypt y que BFF pueden reenviar el token de un usuario
 * @param datosDemo            tarjetas de demostracion creadas al sincronizar
 * @param cajero               reglas del retiro que se aplican a todos los cajeros
 * @param tasas                tasa mensual por tipo de cuenta para una apertura (las mismas del batch)
 * @param clientes             donde esta clientes-service (para verificar al titular en una apertura)
 */
@ConfigurationProperties(prefix = "banco.cuentas")
public record PropiedadesCuentas(String zonaHoraria, boolean sincronizarAlIniciar, Seguridad seguridad,
                                 DatosDemo datosDemo, Cajero cajero, Map<String, BigDecimal> tasas, Clientes clientes) {

    /**
     * @param relevosPermitidos CN de los certificados de los BFF que pueden presentar el token de un usuario
     *                          (token relay); un token de servicio debe venir con el certificado de ese mismo servicio
     */
    public record Seguridad(String pepperTarjetas, int bcryptFuerza, List<String> relevosPermitidos) {
    }

    public record DatosDemo(boolean habilitados, String pin) {
    }

    public record Cajero(BigDecimal limiteDiario) {
    }

    /** @param url base de la API ({@code https://clientes-service/api/v1} con descubrimiento por Eureka) */
    public record Clientes(String url) {
    }
}
