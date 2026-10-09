package com.bancoxyz.bff.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.client.AutoConfigureMockRestServiceServer;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MvcResult;

/** Sesion de servidor, fijacion de sesion y CSRF con el flujo real de cookie y encabezado (sin with(csrf())). */
@ActiveProfiles("prueba")
@SpringBootTest(properties = "spring.jackson.deserialization.fail-on-missing-creator-properties=true")
@AutoConfigureMockMvc
@AutoConfigureMockRestServiceServer
class SeguridadWebTest extends PruebaWeb {

    @Autowired
    private MockRestServiceServer servidor;

    @Override
    protected MockRestServiceServer core() {
        return servidor;
    }

    @Test
    @DisplayName("Sin sesion: 401 JSON, sin redireccion y sin crear una sesion")
    void sinSesion() throws Exception {
        mvc.perform(get("/web/api/panel"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"))
                .andExpect(header().doesNotExist("Location"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    @DisplayName("Login: la identidad queda en la sesion del servidor y la siguiente solicitud se reconoce")
    void loginYSesion() throws Exception {
        MockHttpSession sesion = iniciarSesion(cliente());
        assertThat(sesion).isNotNull();
        mvc.perform(get("/web/api/sesion").session(sesion))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre").value("Jane Smith"))
                .andExpect(jsonPath("$.rol").value("CLIENTE"));
    }

    @Test
    @DisplayName("Login solo acepta application/json (415) y exige usuario y clave (400)")
    void loginSoloJson() throws Exception {
        mvc.perform(post("/web/api/sesion").contentType(MediaType.TEXT_PLAIN).content("usuario=x&password=y"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"));
        mvc.perform(post("/web/api/sesion").contentType(MediaType.APPLICATION_JSON).content("{\"usuario\":\"jane.smith\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"));
    }

    @Test
    @DisplayName("Fijacion de sesion: una sesion previa se invalida y la identidad queda en una sesion con id nuevo")
    void fijacionDeSesion() throws Exception {
        MockHttpSession plantada = new MockHttpSession();
        String idPlantado = plantada.getId();
        servidor.expect(requestTo(CORE + "/autenticacion/usuarios"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(cliente(), MediaType.APPLICATION_JSON));
        MvcResult resultado = mvc.perform(post("/web/api/sesion").session(plantada).contentType(MediaType.APPLICATION_JSON)
                .content("{\"usuario\":\"jane.smith\",\"password\":\"Cliente2026!\"}")).andExpect(status().isOk()).andReturn();
        assertThat(plantada.isInvalid()).isTrue();
        assertThat(resultado.getRequest().getSession(false).getId()).isNotEqualTo(idPlantado);
    }

    @Test
    @DisplayName("CSRF real: con la cookie XSRF-TOKEN reenviada en X-XSRF-TOKEN el logout funciona; sin ella, 403 CSRF_INVALIDO")
    void csrf() throws Exception {
        MockHttpSession sinToken = iniciarSesion(cliente());
        mvc.perform(delete("/web/api/sesion").session(sinToken))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.codigo").value("CSRF_INVALIDO"));

        MvcResult csrf = mvc.perform(get("/web/api/csrf").session(sinToken)).andExpect(status().isOk()).andReturn();
        Cookie cookie = csrf.getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isFalse();
        String token = json.readTree(csrf.getResponse().getContentAsString()).get("token").asText();
        assertThat(token).isEqualTo(cookie.getValue());

        mvc.perform(delete("/web/api/sesion").session(sinToken).cookie(cookie).header("X-XSRF-TOKEN", token))
                .andExpect(status().isNoContent());
        assertThat(sinToken.isInvalid()).isTrue();
    }

    @Test
    @DisplayName("Un cliente en la consola del ejecutivo: 403 ACCESO_DENEGADO")
    void clienteEnRutaDeEjecutivo() throws Exception {
        MockHttpSession sesion = iniciarSesion(cliente());
        mvc.perform(get("/web/api/ejecutivo/calidad").session(sesion))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.codigo").value("ACCESO_DENEGADO"));
    }

    @Test
    @DisplayName("Credenciales invalidas y usuario bloqueado del core se entregan con su codigo")
    void erroresDeLogin() throws Exception {
        servidor.expect(requestTo(CORE + "/autenticacion/usuarios")).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON).body("{\"codigo\":\"CREDENCIALES_INVALIDAS\"}"));
        mvc.perform(post("/web/api/sesion").contentType(MediaType.APPLICATION_JSON).content("{\"usuario\":\"a\",\"password\":\"b\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.codigo").value("CREDENCIALES_INVALIDAS"))
                .andExpect(jsonPath("$.instance").value("/web/api/sesion"));
    }

    @Test
    @DisplayName("Health y contrato OpenAPI publicos; una ruta desconocida autenticada responde 404 con codigo")
    void rutasPublicasYDesconocidas() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andExpect(jsonPath("$.info.title").value("Banco XYZ - BFF Web"));
        MockHttpSession sesion = iniciarSesion(cliente());
        mvc.perform(get("/web/api/no-existe").session(sesion))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.codigo").value("RECURSO_NO_ENCONTRADO"));
    }
}
