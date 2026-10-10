package com.bancoxyz.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * El Config Server sobre el repositorio real (config-server/configuracion/), sin TLS (perfil native solo): exige
 * credenciales, entrega a cada servicio su archivo y el comun, ajusta por entorno y no contiene secretos.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.profiles.active=native", "management.server.port=0",
        "spring.cloud.config.server.native.search-locations=file:./configuracion"})
class ConfiguracionCentralTest {

    /** Los siete servicios que leen su configuracion del Config Server (el batch y Eureka no). */
    private static final List<String> SERVICIOS = List.of("banco-auth", "clientes-service", "cuentas-service",
            "pagos-service", "bff-web", "bff-movil", "bff-cajero");

    @Autowired
    private TestRestTemplate http;

    @Test
    @DisplayName("Sin usuario y clave, el Config Server responde 401 y no entrega nada")
    void exigeCredenciales() {
        assertThat(http.getForEntity("/cuentas-service/default", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(http.withBasicAuth("configuracion", "clave-equivocada")
                .getForEntity("/cuentas-service/default", String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("cuentas-service recibe su archivo y el comun: puertos, management, Eureka, Kafka, JWT y circuitos")
    void configuracionDeCuentas() {
        JsonNode entorno = entorno("cuentas-service", "default");
        assertThat(archivos(entorno)).containsExactly("cuentas-service.properties", "application.properties");
        assertThat(propiedades(entorno))
                .containsEntry("server.port", "8100")
                .containsEntry("management.server.port", "9100")
                .containsEntry("banco.cuentas.clientes.url", "https://clientes-service/api/v1")
                .containsEntry("eureka.instance.instance-id", "${spring.application.name}:${HOSTNAME:local}:${server.port}")
                .containsEntry("resilience4j.circuitbreaker.instances.clientes.base-config", "http-interno")
                .containsEntry("spring.kafka.bootstrap-servers", "localhost:9092")
                .containsEntry("banco.jwt.jwk-set-uri", "https://localhost:8081/oauth2/jwks");
        assertThat(propiedades(entorno).get("management.endpoints.web.exposure.include")).contains("circuitbreakers", "prometheus");
    }

    @Test
    @DisplayName("Perfil nube: direcciones de la red de Docker y Resilience4j mas tolerante, sobre la misma politica")
    void politicasPorEntorno() {
        Map<String, String> local = propiedades(entorno("pagos-service", "default"));
        Map<String, String> nube = propiedades(entorno("pagos-service", "nube"));
        String circuito = "resilience4j.circuitbreaker.configs.http-interno.";
        assertThat(local).containsEntry(circuito + "minimum-number-of-calls", "3")
                .containsEntry(circuito + "wait-duration-in-open-state", "10s")
                .doesNotContainKey(circuito + "slow-call-duration-threshold");
        assertThat(nube).containsEntry("resilience4j.circuitbreaker.instances.cuentas.base-config", "http-interno")
                .containsEntry(circuito + "minimum-number-of-calls", "5")
                .containsEntry(circuito + "wait-duration-in-open-state", "15s")
                .containsEntry(circuito + "slow-call-duration-threshold", "2s")
                .containsEntry("resilience4j.retry.configs.http-interno.enable-exponential-backoff", "true")
                .containsEntry("spring.datasource.url", "jdbc:postgresql://db:5432/banco_xyz")
                .containsEntry("banco.jwt.emisor", "https://banco-auth:8081")
                .containsEntry("eureka.instance.prefer-ip-address", "true")
                .containsEntry("eureka.client.service-url.defaultZone", "http://eureka-1:8761/eureka/,http://eureka-2:8762/eureka/");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"banco-auth", "clientes-service", "cuentas-service", "pagos-service", "bff-web", "bff-movil", "bff-cajero"})
    @DisplayName("Cada circuito de cada servicio usa una politica compartida (base-config), no una propia")
    void circuitosConPoliticaCompartida(String servicio) {
        Map<String, String> configuracion = propiedades(entorno(servicio, "nube"));
        configuracion.forEach((clave, valor) -> {
            if (clave.matches("resilience4j\\.(circuitbreaker|retry|bulkhead)\\.instances\\.[a-z]+\\.base-config")) {
                assertThat(valor).isIn("http-interno", "publicacion-kafka");
            }
        });
        assertThat(configuracion.keySet()).noneMatch(clave -> clave.matches(
                "resilience4j\\.circuitbreaker\\.instances\\.[a-z]+\\.(sliding-window-size|failure-rate-threshold)"));
    }

    @Test
    @DisplayName("Cada servicio tiene su puerto de negocio y su puerto de operacion, sin choques entre ellos")
    void puertosUnicos() {
        Set<String> puertos = new HashSet<>();
        for (String servicio : SERVICIOS) {
            Map<String, String> configuracion = propiedades(entorno(servicio, "default"));
            assertThat(puertos.add(configuracion.get("server.port"))).as(servicio + " server.port").isTrue();
            assertThat(puertos.add(configuracion.get("management.server.port"))).as(servicio + " management").isTrue();
        }
        assertThat(puertos).hasSize(SERVICIOS.size() * 2);
    }

    @Test
    @DisplayName("banco-auth recibe el registro OAuth2 de GitHub y sus vinculos; el client-id es un marcador y no hay client-secret")
    void registroDeGitHub() {
        String github = "spring.security.oauth2.client.registration.github.";
        assertThat(propiedades(entorno("banco-auth", "nube")))
                .containsEntry(github + "client-id", "${BANCO_GITHUB_CLIENT_ID:sin-configurar}")
                .containsEntry(github + "scope", "read:user,user:email")
                .containsEntry(github + "redirect-uri", "{baseUrl}/login/oauth2/code/{registrationId}")
                .containsEntry("banco.auth.github.vinculos.113071563", "diana.prince")
                .doesNotContainKey(github + "client-secret");
    }

    @Test
    @DisplayName("pagos-service recibe sus reglas de fraude desde la configuracion central")
    void reglasDeFraude() {
        assertThat(propiedades(entorno("pagos-service", "default")))
                .containsEntry("banco.pagos.fraude.monto-maximo", "5000000")
                .containsEntry("banco.pagos.fraude.cuentas-en-observacion", "104");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"banco-auth", "clientes-service", "cuentas-service", "pagos-service", "bff-web", "bff-movil", "bff-cajero"})
    @DisplayName("Ningun servicio recibe claves ni secretos desde la configuracion central")
    void sinSecretos(String servicio) {
        assertThat(propiedades(entorno(servicio, "nube")).keySet())
                .noneMatch(clave -> clave.matches("(?i).*(password|clave|secret|secreto|pepper).*"));
    }

    private JsonNode entorno(String servicio, String perfil) {
        ResponseEntity<JsonNode> respuesta = http.withBasicAuth("configuracion", "config-secreto-dev")
                .getForEntity("/" + servicio + "/" + perfil, JsonNode.class);
        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
        return respuesta.getBody();
    }

    /** Nombre de archivo de cada fuente, en orden de prioridad (la primera gana). */
    private static List<String> archivos(JsonNode entorno) {
        return entorno.get("propertySources").findValuesAsText("name").stream()
                .map(nombre -> nombre.substring(nombre.lastIndexOf('/') + 1)).toList();
    }

    /** Propiedades efectivas: la fuente especifica gana sobre la comun, como en el cliente. */
    private static Map<String, String> propiedades(JsonNode entorno) {
        Map<String, String> resultado = new HashMap<>();
        List<JsonNode> fuentes = entorno.get("propertySources").findValues("source");
        for (int i = fuentes.size() - 1; i >= 0; i--) {
            fuentes.get(i).properties().forEach(par -> resultado.put(par.getKey(), par.getValue().asText()));
        }
        return resultado;
    }
}
