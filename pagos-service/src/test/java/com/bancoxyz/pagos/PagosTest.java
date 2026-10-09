package com.bancoxyz.pagos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.x509;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.bancoxyz.pagos.cuentas.ClienteDeCuentas;
import com.bancoxyz.pagos.cuentas.Validacion;
import com.bancoxyz.pagos.evento.EventoDeMovimiento;
import com.bancoxyz.pagos.pago.ServicioDePagos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Pagos con el token del usuario reenviado por el BFF: registro, idempotencia, fraude y resultado de la saga. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("prueba")
@Import(ConfiguracionDePrueba.class)
class PagosTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ServicioDePagos servicio;

    @MockitoBean
    private ClienteDeCuentas cuentas;

    @BeforeEach
    void limpiar() {
        for (String tabla : List.of("historial", "pago", "evento_procesado", "outbox")) {
            jdbc.sql("delete from pagos." + tabla).update();
        }
        when(cuentas.validarCuentaDeOrigen(anyLong(), anyLong())).thenReturn(Validacion.PREVIA);
    }

    private static RequestPostProcessor usuario(String bff, String rol, Long clienteId, String... scopes) {
        List<String> lista = Arrays.asList(scopes);
        RequestPostProcessor token = jwt().jwt(j -> {
                    j.subject("diana.prince").claim("usuario_id", 14L).claim("rol", rol).claim("scope", lista);
                    if (clienteId != null) {
                        j.claim("cliente_id", clienteId);
                    }
                })
                .authorities(lista.stream().map(s -> new SimpleGrantedAuthority("SCOPE_" + s)).toArray(SimpleGrantedAuthority[]::new));
        RequestPostProcessor certificado = x509(PkiDePrueba.certificado(bff));
        return s -> certificado.postProcessRequest(token.postProcessRequest(s));
    }

    private static RequestPostProcessor diana(String... scopes) {
        return usuario("bff-web", "CLIENTE", 4L, scopes);
    }

    private ResultActions transferir(String clave, String cuerpo) throws Exception {
        return mvc.perform(post("/api/v1/pagos/transferencias").with(diana("pagos.escribir")).header("Idempotency-Key", clave)
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo));
    }

    private List<String> outbox() {
        return jdbc.sql("select topico || ':' || tipo from pagos.outbox order by id").query(String.class).list();
    }

    @Test
    @DisplayName("Transferencia: 202 Accepted con Location, estado SOLICITADO y PagoSolicitado en el outbox")
    void transferencia() throws Exception {
        transferir("clave-00000001", "{\"cuentaOrigen\":101,\"cuentaDestino\":102,\"monto\":1500}")
                .andExpect(status().isAccepted()).andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.estado").value("SOLICITADO")).andExpect(jsonPath("$.validacion").value("PREVIA"))
                .andExpect(jsonPath("$.canal").value("WEB")).andExpect(jsonPath("$.historial[0].evento").value("PagoSolicitado"));
        assertThat(outbox()).containsExactly("pagos.solicitados:PagoSolicitado");
        JsonNode evento = json.readTree(jdbc.sql("select payload from pagos.outbox").query(String.class).single());
        assertThat(evento.get("operacion").asText()).isEqualTo("TRANSFERENCIA");
        assertThat(evento.get("clienteId").asLong()).isEqualTo(4);
    }

    @Test
    @DisplayName("La misma Idempotency-Key: 200 con el mismo pago; con otro contenido: 409 IDEMPOTENCIA_CONFLICTO")
    void idempotencia() throws Exception {
        String id = json.readTree(transferir("clave-00000002", "{\"cuentaOrigen\":101,\"cuentaDestino\":102,\"monto\":1000}")
                .andReturn().getResponse().getContentAsString()).get("id").asText();
        transferir("clave-00000002", "{\"cuentaOrigen\":101,\"cuentaDestino\":102,\"monto\":1000}").andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true")).andExpect(jsonPath("$.id").value(id));
        transferir("clave-00000002", "{\"cuentaOrigen\":101,\"cuentaDestino\":102,\"monto\":2000}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("IDEMPOTENCIA_CONFLICTO"));
        assertThat(outbox()).hasSize(1);
    }

    @Test
    @DisplayName("Fraude: monto sobre el limite -> RECHAZADO y alerta OperacionSospechosa; no se publica el pago")
    void fraude() throws Exception {
        transferir("clave-00000003", "{\"cuentaOrigen\":101,\"cuentaDestino\":102,\"monto\":6000000}")
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.estado").value("RECHAZADO"))
                .andExpect(jsonPath("$.motivo").value("MONTO_SOBRE_LIMITE"));
        transferir("clave-00000004", "{\"cuentaOrigen\":101,\"cuentaDestino\":666,\"monto\":100}")
                .andExpect(jsonPath("$.motivo").value("CUENTA_EN_OBSERVACION"));
        assertThat(outbox()).containsExactly("seguridad.alertas:OperacionSospechosa", "seguridad.alertas:OperacionSospechosa");
    }

    @Test
    @DisplayName("Deposito y pago de servicio; desde la app movil el canal queda MOVIL")
    void depositoYServicio() throws Exception {
        mvc.perform(post("/api/v1/pagos/depositos").with(usuario("bff-movil", "CLIENTE", 4L, "pagos.escribir"))
                        .header("Idempotency-Key", "clave-00000005").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cuentaDestino\":105,\"monto\":3000}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.operacion").value("DEPOSITO"))
                .andExpect(jsonPath("$.canal").value("MOVIL"));
        mvc.perform(post("/api/v1/pagos/servicios").with(diana("pagos.escribir")).header("Idempotency-Key", "clave-00000006")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"cuentaOrigen\":105,\"convenio\":\"Luz Chile\",\"monto\":500}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.convenio").value("Luz Chile"));
    }

    @Test
    @DisplayName("Validaciones: monto con decimales, misma cuenta, sin Idempotency-Key; un ejecutivo no paga")
    void rechazosDeEntrada() throws Exception {
        transferir("clave-00000007", "{\"cuentaOrigen\":101,\"cuentaDestino\":102,\"monto\":10.5}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errores[0].campo").value("monto"));
        transferir("clave-00000008", "{\"cuentaOrigen\":101,\"cuentaDestino\":101,\"monto\":10}")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.codigo").value("MISMA_CUENTA"));
        mvc.perform(post("/api/v1/pagos/transferencias").with(diana("pagos.escribir")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"cuentaOrigen\":101,\"cuentaDestino\":102,\"monto\":10}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"));
        mvc.perform(post("/api/v1/pagos/transferencias").with(usuario("bff-web", "EJECUTIVO", null, "pagos.escribir"))
                        .header("Idempotency-Key", "clave-00000009").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cuentaOrigen\":101,\"cuentaDestino\":102,\"monto\":10}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.codigo").value("ACCESO_DENEGADO"));
    }

    @Test
    @DisplayName("cuentas-service no responde: el pago se acepta con validacion DIFERIDA (fallback)")
    void validacionDiferida() throws Exception {
        when(cuentas.validarCuentaDeOrigen(anyLong(), anyLong())).thenReturn(Validacion.DIFERIDA);
        transferir("clave-00000010", "{\"cuentaOrigen\":101,\"cuentaDestino\":102,\"monto\":100}")
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.validacion").value("DIFERIDA"));
    }

    @Test
    @DisplayName("Resultado de cuentas-service: COMPLETADO; un evento repetido o atrasado no cambia el estado final")
    void resultado() throws Exception {
        String id = json.readTree(transferir("clave-00000011", "{\"cuentaOrigen\":101,\"cuentaDestino\":102,\"monto\":100}")
                .andReturn().getResponse().getContentAsString()).get("id").asText();
        EventoDeMovimiento completada = new EventoDeMovimiento(1, UUID.randomUUID().toString(), EventoDeMovimiento.COMPLETADA,
                id, Instant.now(), "TRANSFERENCIA", 4L, 101L, 102L, new BigDecimal("100"), "CLP", null, new BigDecimal("7940"), null);
        servicio.registrarResultado(completada);
        servicio.registrarResultado(completada);
        servicio.registrarResultado(new EventoDeMovimiento(1, UUID.randomUUID().toString(), EventoDeMovimiento.RECHAZADA,
                id, Instant.now(), "TRANSFERENCIA", 4L, 101L, 102L, new BigDecimal("100"), "CLP", "SALDO_INSUFICIENTE", null, null));

        mvc.perform(get("/api/v1/pagos/" + id).with(diana("pagos.leer"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("COMPLETADO")).andExpect(jsonPath("$.historial.length()").value(3));
        mvc.perform(get("/api/v1/pagos/" + id).with(usuario("bff-web", "CLIENTE", 5L, "pagos.leer")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.codigo").value("PAGO_NO_ENCONTRADO"));
        mvc.perform(get("/api/v1/pagos").with(diana("pagos.leer"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("Sin el scope: 403; token presentado sin el certificado de un BFF: 403 CERTIFICADO_NO_CORRESPONDE")
    void seguridad() throws Exception {
        mvc.perform(post("/api/v1/pagos/transferencias").with(diana("pagos.leer")).header("Idempotency-Key", "clave-00000012")
                .contentType(MediaType.APPLICATION_JSON).content("{\"cuentaOrigen\":101,\"cuentaDestino\":102,\"monto\":10}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/pagos").with(usuario("bff-cajero", "CLIENTE", 4L, "pagos.leer")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.codigo").value("CERTIFICADO_NO_CORRESPONDE"));
    }
}
