package com.bancoxyz.clientes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Verificacion de credenciales para banco-auth: clave de servicio + certificado, bloqueo y alerta de seguridad. */
class AutenticacionTest extends PruebaDeClientes {

    private ResultActions autenticar(String usuario, String clave, RequestPostProcessor credencial) throws Exception {
        return mvc.perform(post("/api/v1/autenticacion/usuarios").with(credencial).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("usuario", usuario, "password", clave))));
    }

    @Test
    @DisplayName("Un cliente migrado ingresa con su clave: banco-auth recibe su usuario, su cliente (id de la migracion) y su rol")
    void clienteAutenticado() throws Exception {
        autenticar("diana.prince", "Cliente2026!", bancoAuth()).andExpect(status().isOk())
                .andExpect(jsonPath("$.usuario").value("diana.prince"))
                .andExpect(jsonPath("$.clienteId").value(DIANA))
                .andExpect(jsonPath("$.nombre").value("Diana Prince"))
                .andExpect(jsonPath("$.rol").value("CLIENTE"));
    }

    @Test
    @DisplayName("El ejecutivo tambien se verifica aqui; que aplicacion admite su rol lo decide banco-auth")
    void ejecutivoAutenticado() throws Exception {
        autenticar("ejecutivo", "Ejecutivo2026!", bancoAuth()).andExpect(status().isOk())
                .andExpect(jsonPath("$.rol").value("EJECUTIVO"))
                .andExpect(jsonPath("$.clienteId").doesNotExist());
    }

    @Test
    @DisplayName("Clave incorrecta: 401 CREDENCIALES_INVALIDAS con el ProblemDetail comun")
    void claveIncorrecta() throws Exception {
        autenticar("diana.prince", "otra-clave", bancoAuth()).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("urn:bancoxyz:problema:credenciales-invalidas"))
                .andExpect(jsonPath("$.codigo").value("CREDENCIALES_INVALIDAS"))
                .andExpect(jsonPath("$.instance").value("/api/v1/autenticacion/usuarios"))
                .andExpect(jsonPath("$.correlacionId").isNotEmpty());
    }

    @Test
    @DisplayName("Quinto intento fallido: 423 USUARIO_BLOQUEADO y una alerta UsuarioBloqueado en el outbox, en la misma transaccion")
    void bloqueoConAlerta() throws Exception {
        for (int i = 0; i < 4; i++) {
            autenticar("diana.prince", "mala-" + i, bancoAuth()).andExpect(status().isUnauthorized());
        }
        autenticar("diana.prince", "mala-4", bancoAuth()).andExpect(status().isLocked())
                .andExpect(jsonPath("$.codigo").value("USUARIO_BLOQUEADO"));
        // Bloqueado, ni la clave correcta entra.
        autenticar("diana.prince", "Cliente2026!", bancoAuth()).andExpect(status().isLocked());

        var alertas = jdbc.sql("select topico, clave, tipo, payload from clientes.outbox").query().listOfRows();
        assertThat(alertas).hasSize(1);
        assertThat(alertas.get(0)).containsEntry("topico", "seguridad.alertas").containsEntry("tipo", "UsuarioBloqueado")
                .containsEntry("clave", String.valueOf(DIANA));
        JsonNode alerta = json.readTree((String) alertas.get(0).get("payload"));
        assertThat(alerta.get("version").asInt()).isEqualTo(1);
        assertThat(alerta.get("clienteId").asLong()).isEqualTo(DIANA);
        assertThat(alerta.get("severidad").asText()).isEqualTo("ALTA");
        assertThat(alerta.get("referencia").asText()).isEqualTo("diana.prince");
    }

    @Test
    @DisplayName("Pasados los 15 minutos el bloqueo vence y la clave correcta vuelve a entrar")
    void bloqueoVence() throws Exception {
        for (int i = 0; i < 5; i++) {
            autenticar("steve.rogers", "mala-" + i, bancoAuth());
        }
        reloj.fijar(LocalDateTime.now(reloj).plusMinutes(16));
        autenticar("steve.rogers", "Cliente2026!", bancoAuth()).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Identificar sin clave (GitHub): solo clientes activos; el ejecutivo y el inexistente se rechazan")
    void identificar() throws Exception {
        mvc.perform(get("/api/v1/autenticacion/usuarios/alice.brown").with(bancoAuth())).andExpect(status().isOk())
                .andExpect(jsonPath("$.clienteId").value(ALICE));
        mvc.perform(get("/api/v1/autenticacion/usuarios/ejecutivo").with(bancoAuth())).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("ROL_NO_PERMITIDO"));
        mvc.perform(get("/api/v1/autenticacion/usuarios/nadie").with(bancoAuth())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("USUARIO_NO_ENCONTRADO"));
    }

    @Test
    @DisplayName("Sin certificado de cliente: 401 CERTIFICADO_REQUERIDO aunque la clave de servicio sea correcta")
    void sinCertificado() throws Exception {
        autenticar("diana.prince", "Cliente2026!", org.springframework.security.test.web.servlet.request
                .SecurityMockMvcRequestPostProcessors.httpBasic("banco-auth", "autenticacion-secreto-dev"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.codigo").value("CERTIFICADO_REQUERIDO"));
    }

    @Test
    @DisplayName("Clave de banco-auth con el certificado de otro servicio: 403 CERTIFICADO_NO_CORRESPONDE")
    void certificadoDeOtro() throws Exception {
        autenticar("diana.prince", "Cliente2026!", servicio("banco-auth", "autenticacion-secreto-dev", "bff-web"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.codigo").value("CERTIFICADO_NO_CORRESPONDE"));
    }

    @Test
    @DisplayName("Clave de servicio incorrecta o un token de usuario en esta puerta: 401 NO_AUTENTICADO")
    void credencialEquivocada() throws Exception {
        autenticar("diana.prince", "Cliente2026!", servicio("banco-auth", "otra", "banco-auth"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"));
        // Un access token, aunque venga con el certificado de banco-auth, no abre esta puerta: aqui solo vale la clave.
        RequestPostProcessor certificado = org.springframework.security.test.web.servlet.request
                .SecurityMockMvcRequestPostProcessors.x509(PkiDePrueba.certificado("banco-auth"));
        autenticar("diana.prince", "Cliente2026!", s -> {
            s.addHeader("Authorization", "Bearer eyJhbGciOiJSUzI1NiJ9.e30.firma");
            return certificado.postProcessRequest(s);
        }).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"));
    }

    @Test
    @DisplayName("Cuerpo invalido: 400 con el campo que fallo")
    void cuerpoInvalido() throws Exception {
        autenticar("", "x", bancoAuth()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"))
                .andExpect(jsonPath("$.errores[0].campo").value("usuario"));
    }
}
