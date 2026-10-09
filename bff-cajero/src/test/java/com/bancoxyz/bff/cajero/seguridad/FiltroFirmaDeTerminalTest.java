package com.bancoxyz.bff.cajero.seguridad;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import com.bancoxyz.bff.cajero.config.PropiedadesCajero;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Los vectores de la especificacion (11.10) con el reloj fijo en su marca de tiempo. El mismo vector lo usa
 * scripts/firmar_cajero.sh: si el filtro y el firmador divergieran en un byte, falla uno de los dos.
 */
class FiltroFirmaDeTerminalTest {

    static final String SECRETO = "atm-001-secreto-de-desarrollo-0123456789";
    static final long MARCA = 1767225600L;
    static final String CUERPO_VECTOR_1 = "{\"monto\":3000}";
    static final String CLAVE_VECTOR_1 = "3f1c2a7e-9b1d-4c1e-8f00-000000000001";

    private final RelojMovible reloj = new RelojMovible(Instant.ofEpochSecond(MARCA));
    private final FiltroFirmaDeTerminal filtro = new FiltroFirmaDeTerminal(new PropiedadesCajero(
            new PropiedadesCajero.Jwt("x".repeat(40)),
            List.of(new PropiedadesCajero.Terminal("ATM-001", SECRETO, "Sucursal Centro")),
            new BigDecimal("1000"), new BigDecimal("200000"), 30, 120),
            reloj, new RespuestasDeSeguridad(new ObjectMapper()));

    @Test
    @DisplayName("Los tres vectores de la especificacion producen exactamente la firma esperada")
    void vectores() {
        assertThat(FirmaHmac.firmar(SECRETO, "POST", "/cajero/v1/retiros", "1767225600", "n0nc3-0000000001",
                CLAVE_VECTOR_1, CUERPO_VECTOR_1.getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("W7evtUL9A5D6kipDrpG3JYYPSiVqCvWuNXgXgIpi3qQ=");
        assertThat(FirmaHmac.firmar(SECRETO, "GET", "/cajero/v1/saldo", "1767225600", "n0nc3-0000000002", null, new byte[0]))
                .isEqualTo("GZENspHNwt0pLI/TEjooPZtqhrWi9ml57BqUdgqDLBU=");
        assertThat(FirmaHmac.firmar(SECRETO, "GET", "/cajero/v1/saldo?formato=corto", "1767225600", "n0nc3-0000000003",
                "", new byte[0]))
                .isEqualTo("q4TLLd+aadV9gsmmg6qJ9sQNWvQThLvzGXB7bzR9JQU=");
    }

    @Test
    @DisplayName("Una solicitud firmada pasa y el controlador recibe el cuerpo intacto")
    void firmaValidaYCuerpoIntacto() throws Exception {
        MockFilterChain cadena = new MockFilterChain();
        MockHttpServletResponse respuesta = new MockHttpServletResponse();
        filtro.doFilter(vector1("W7evtUL9A5D6kipDrpG3JYYPSiVqCvWuNXgXgIpi3qQ="), respuesta, cadena);

        assertThat(respuesta.getStatus()).isEqualTo(200);
        HttpServletRequest recibida = (HttpServletRequest) cadena.getRequest();
        assertThat(new String(recibida.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(CUERPO_VECTOR_1);
        assertThat(recibida.getReader().readLine()).isEqualTo(CUERPO_VECTOR_1);
        assertThat(recibida.getContentLength()).isEqualTo(CUERPO_VECTOR_1.length());
    }

    @Test
    @DisplayName("Con query la ruta firmada incluye ?query cruda")
    void vectorConQuery() throws Exception {
        MockHttpServletRequest solicitud = new MockHttpServletRequest("GET", "/cajero/v1/saldo");
        solicitud.setQueryString("formato=corto");
        encabezados(solicitud, "ATM-001", MARCA, "n0nc3-0000000003", "q4TLLd+aadV9gsmmg6qJ9sQNWvQThLvzGXB7bzR9JQU=");
        MockHttpServletResponse respuesta = new MockHttpServletResponse();
        MockFilterChain cadena = new MockFilterChain();
        filtro.doFilter(solicitud, respuesta, cadena);
        assertThat(cadena.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("Encabezados faltantes, terminal desconocido, nonce corto o marca ilegible: 401 FIRMA_INVALIDA")
    void rechazosDeFormato() throws Exception {
        assertThat(rechazo(new MockHttpServletRequest("GET", "/cajero/v1/saldo"))).contains("FIRMA_INVALIDA");
        MockHttpServletRequest desconocido = vector1("W7evtUL9A5D6kipDrpG3JYYPSiVqCvWuNXgXgIpi3qQ=");
        desconocido.removeHeader("X-Terminal-Id");
        desconocido.addHeader("X-Terminal-Id", "ATM-999");
        assertThat(rechazo(desconocido)).contains("FIRMA_INVALIDA").contains("DEVOLVER_TARJETA");
        MockHttpServletRequest nonceCorto = new MockHttpServletRequest("GET", "/cajero/v1/saldo");
        encabezados(nonceCorto, "ATM-001", MARCA, "corto", "xx");
        assertThat(rechazo(nonceCorto)).contains("FIRMA_INVALIDA");
        MockHttpServletRequest marcaIlegible = new MockHttpServletRequest("GET", "/cajero/v1/saldo");
        marcaIlegible.addHeader("X-Terminal-Id", "ATM-001");
        marcaIlegible.addHeader("X-Marca-Tiempo", "ayer");
        marcaIlegible.addHeader("X-Nonce", "n0nc3-0000000002");
        marcaIlegible.addHeader("X-Firma", "GZENspHNwt0pLI/TEjooPZtqhrWi9ml57BqUdgqDLBU=");
        assertThat(rechazo(marcaIlegible)).contains("FIRMA_INVALIDA");
        assertThat(rechazo(vector1("%%%no-es-base64%%%"))).contains("FIRMA_INVALIDA");
    }

    @Test
    @DisplayName("Marca de tiempo a mas de 30 s: 401 MARCA_TIEMPO_FUERA_DE_VENTANA")
    void marcaFueraDeVentana() throws Exception {
        reloj.mover(31);
        assertThat(rechazo(vector1("W7evtUL9A5D6kipDrpG3JYYPSiVqCvWuNXgXgIpi3qQ="))).contains("MARCA_TIEMPO_FUERA_DE_VENTANA");
    }

    @Test
    @DisplayName("Firma alterada o Idempotency-Key cambiada en el camino: 401 FIRMA_INVALIDA")
    void firmaAlterada() throws Exception {
        assertThat(rechazo(vector1("X7evtUL9A5D6kipDrpG3JYYPSiVqCvWuNXgXgIpi3qQ="))).contains("FIRMA_INVALIDA");
        MockHttpServletRequest claveCambiada = vector1("W7evtUL9A5D6kipDrpG3JYYPSiVqCvWuNXgXgIpi3qQ=");
        claveCambiada.removeHeader("Idempotency-Key");
        claveCambiada.addHeader("Idempotency-Key", "otra-clave-para-cobrar-dos-veces");
        assertThat(rechazo(claveCambiada)).contains("FIRMA_INVALIDA");
    }

    @Test
    @DisplayName("Nonce repetido: 401 NONCE_REPETIDO; pero un intento con firma invalida no quema el nonce")
    void nonces() throws Exception {
        assertThat(rechazo(vector1("X7evtUL9A5D6kipDrpG3JYYPSiVqCvWuNXgXgIpi3qQ="))).contains("FIRMA_INVALIDA");
        assertThat(pasa(vector1("W7evtUL9A5D6kipDrpG3JYYPSiVqCvWuNXgXgIpi3qQ="))).isTrue();
        assertThat(rechazo(vector1("W7evtUL9A5D6kipDrpG3JYYPSiVqCvWuNXgXgIpi3qQ="))).contains("NONCE_REPETIDO");
    }

    @Test
    @DisplayName("Los nonces mas viejos que dos ventanas se desalojan")
    void desalojo() throws Exception {
        assertThat(pasa(vector1("W7evtUL9A5D6kipDrpG3JYYPSiVqCvWuNXgXgIpi3qQ="))).isTrue();
        assertThat(filtro.noncesRegistrados()).isEqualTo(1);
        reloj.mover(61);
        long marca = MARCA + 61;
        MockHttpServletRequest nueva = new MockHttpServletRequest("GET", "/cajero/v1/saldo");
        encabezados(nueva, "ATM-001", marca, "n0nc3-0000000099", FirmaHmac.firmar(SECRETO, "GET", "/cajero/v1/saldo",
                String.valueOf(marca), "n0nc3-0000000099", null, new byte[0]));
        assertThat(pasa(nueva)).isTrue();
        assertThat(filtro.noncesRegistrados()).isEqualTo(1);
    }

    @Test
    @DisplayName("Fuera de /cajero/v1 (health, swagger) no se exige firma")
    void rutasSinFirma() throws Exception {
        assertThat(pasa(new MockHttpServletRequest("GET", "/actuator/health"))).isTrue();
    }

    private MockHttpServletRequest vector1(String firma) {
        MockHttpServletRequest solicitud = new MockHttpServletRequest("POST", "/cajero/v1/retiros");
        encabezados(solicitud, "ATM-001", MARCA, "n0nc3-0000000001", firma);
        solicitud.addHeader("Idempotency-Key", CLAVE_VECTOR_1);
        solicitud.setContentType("application/json");
        solicitud.setContent(CUERPO_VECTOR_1.getBytes(StandardCharsets.UTF_8));
        return solicitud;
    }

    private static void encabezados(MockHttpServletRequest solicitud, String terminal, long marca, String nonce, String firma) {
        solicitud.addHeader("X-Terminal-Id", terminal);
        solicitud.addHeader("X-Marca-Tiempo", String.valueOf(marca));
        solicitud.addHeader("X-Nonce", nonce);
        solicitud.addHeader("X-Firma", firma);
    }

    private String rechazo(MockHttpServletRequest solicitud) throws Exception {
        MockHttpServletResponse respuesta = new MockHttpServletResponse();
        MockFilterChain cadena = new MockFilterChain();
        filtro.doFilter(solicitud, respuesta, cadena);
        assertThat(respuesta.getStatus()).isEqualTo(401);
        assertThat(cadena.getRequest()).as("la solicitud rechazada no debe llegar al controlador").isNull();
        return respuesta.getContentAsString(StandardCharsets.UTF_8);
    }

    private boolean pasa(MockHttpServletRequest solicitud) throws Exception {
        MockFilterChain cadena = new MockFilterChain();
        filtro.doFilter(solicitud, new MockHttpServletResponse(), cadena);
        return cadena.getRequest() != null;
    }

    static final class RelojMovible extends Clock {
        private Instant instante;

        RelojMovible(Instant instante) {
            this.instante = instante;
        }

        void mover(long segundos) {
            instante = instante.plusSeconds(segundos);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instante;
        }
    }
}
