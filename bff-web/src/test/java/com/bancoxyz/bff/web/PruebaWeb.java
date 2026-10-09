package com.bancoxyz.bff.web;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/** Utilidades comunes: login contra el core simulado y lectura de los contratos. */
abstract class PruebaWeb {

    static final String CORE = "https://localhost:8080/api/v1";

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper json;

    protected abstract MockRestServiceServer core();

    protected MockHttpSession iniciarSesion(String usuarioDelCore) throws Exception {
        core().expect(requestTo(CORE + "/autenticacion/usuarios")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(usuarioDelCore, MediaType.APPLICATION_JSON));
        MvcResult resultado = mvc.perform(post("/web/api/sesion").contentType(MediaType.APPLICATION_JSON)
                .content("{\"usuario\":\"usuario\",\"password\":\"Cliente2026!\"}")).andExpect(status().isOk()).andReturn();
        core().verify();
        core().reset();
        return (MockHttpSession) resultado.getRequest().getSession(false);
    }

    protected static String cliente() throws IOException {
        return contrato("usuario-autenticado.json");
    }

    protected static String ejecutivo() throws IOException {
        return contrato("usuario-autenticado.json").replace("\"usuarioId\": 3", "\"usuarioId\": 1")
                .replace("\"clienteId\": 6", "\"clienteId\": null").replace("\"rol\": \"CLIENTE\"", "\"rol\": \"EJECUTIVO\"")
                .replace("\"nombre\": \"Jane Smith\"", "\"nombre\": \"Ejecutivo del banco\"");
    }

    protected static String contrato(String archivo) throws IOException {
        return Files.readString(Path.of("contratos", "core-api", archivo));
    }

    protected JsonNode cuerpo(ResultActions resultado) throws Exception {
        return json.readTree(resultado.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
