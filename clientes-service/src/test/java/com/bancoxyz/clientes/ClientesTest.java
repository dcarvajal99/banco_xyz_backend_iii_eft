package com.bancoxyz.clientes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Ficha, perfil y alta de clientes con el token del usuario: scope por operacion y rol/cliente del propio token. */
class ClientesTest extends PruebaDeClientes {

    private static final String PERFIL = "{\"email\":\"diana@correo.cl\",\"telefono\":\"+56 9 1234 5678\",\"direccion\":\"Av. Siempre Viva 742\"}";

    private String alta(String nombre, String usuario) throws Exception {
        return json.writeValueAsString(Map.of("nombre", nombre, "edad", 31, "usuario", usuario,
                "claveInicial", "ClaveInicial2026", "perfil", Map.of("email", "nuevo@correo.cl", "telefono", "", "direccion", "")));
    }

    @Test
    @DisplayName("El ejecutivo lista los clientes, en orden, con su usuario")
    void ejecutivoLista() throws Exception {
        mvc.perform(get("/api/v1/clientes").with(ejecutivo("clientes.leer"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(7))
                .andExpect(jsonPath("$[0].nombre").value("Alice Brown"))
                .andExpect(jsonPath("$[0].usuario").value("alice.brown"))
                .andExpect(jsonPath("$[0].origen").value("MIGRACION"));
    }

    @Test
    @DisplayName("Un cliente no lista a los demas: 403 ACCESO_DENEGADO aunque tenga el scope")
    void clienteNoLista() throws Exception {
        mvc.perform(get("/api/v1/clientes").with(cliente("diana.prince", 4, DIANA, "clientes.leer")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.codigo").value("ACCESO_DENEGADO"));
    }

    @Test
    @DisplayName("Ficha propia si; la de otro cliente responde 404, igual que una inexistente")
    void fichaPropia() throws Exception {
        mvc.perform(get("/api/v1/clientes/" + DIANA).with(cliente("diana.prince", 4, DIANA, "clientes.leer")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nombre").value("Diana Prince"))
                .andExpect(jsonPath("$.edad").value(35)).andExpect(jsonPath("$.usuario").value("diana.prince"));
        mvc.perform(get("/api/v1/clientes/" + STEVE).with(cliente("diana.prince", 4, DIANA, "clientes.leer")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.codigo").value("CLIENTE_NO_ENCONTRADO"));
        mvc.perform(get("/api/v1/clientes/999").with(ejecutivo("clientes.leer"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/clientes/" + STEVE).with(ejecutivo("clientes.leer"))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("El cliente actualiza su perfil; la version sube y el cambio queda guardado")
    void actualizaPerfil() throws Exception {
        mvc.perform(put("/api/v1/clientes/" + DIANA + "/perfil").with(cliente("diana.prince", 4, DIANA, "clientes.perfil"))
                        .contentType(MediaType.APPLICATION_JSON).content(PERFIL))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value("diana@correo.cl"))
                .andExpect(jsonPath("$.version").value(1));
        assertThat(jdbc.sql("select telefono from clientes.cliente where id = 4").query(String.class).single())
                .isEqualTo("+56 9 1234 5678");
    }

    @Test
    @DisplayName("Perfil invalido: 400 con los campos que fallaron; perfil ajeno: 404; sin el scope: 403")
    void perfilRechazado() throws Exception {
        mvc.perform(put("/api/v1/clientes/" + DIANA + "/perfil").with(cliente("diana.prince", 4, DIANA, "clientes.perfil"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"no-es-correo\",\"telefono\":\"12\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"))
                .andExpect(jsonPath("$.errores.length()").value(2));
        mvc.perform(put("/api/v1/clientes/" + STEVE + "/perfil").with(cliente("diana.prince", 4, DIANA, "clientes.perfil"))
                .contentType(MediaType.APPLICATION_JSON).content(PERFIL)).andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/clientes/" + DIANA + "/perfil").with(cliente("diana.prince", 4, DIANA, "clientes.leer"))
                        .contentType(MediaType.APPLICATION_JSON).content(PERFIL))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.codigo").value("ACCESO_DENEGADO"));
    }

    @Test
    @DisplayName("Alta por un ejecutivo: 201 con Location, id del rango de altas y credenciales que banco-auth ya puede verificar")
    void altaDeCliente() throws Exception {
        mvc.perform(post("/api/v1/clientes").with(ejecutivo("clientes.administrar")).contentType(MediaType.APPLICATION_JSON)
                        .content(alta("Peter Parker", "peter.parker")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/v1/clientes/")))
                .andExpect(jsonPath("$.clienteId").value(greaterThanOrEqualTo(100001)))
                .andExpect(jsonPath("$.origen").value("ALTA"))
                .andExpect(jsonPath("$.usuario").value("peter.parker"));
        mvc.perform(post("/api/v1/autenticacion/usuarios").with(bancoAuth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"usuario\":\"peter.parker\",\"password\":\"ClaveInicial2026\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rol").value("CLIENTE"));
    }

    @Test
    @DisplayName("Alta repetida (nombre o usuario): 409; por un cliente: 403; con datos invalidos: 400")
    void altaRechazada() throws Exception {
        mvc.perform(post("/api/v1/clientes").with(ejecutivo("clientes.administrar")).contentType(MediaType.APPLICATION_JSON)
                .content(alta("Diana Prince", "otra.diana"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("CLIENTE_YA_EXISTE"));
        mvc.perform(post("/api/v1/clientes").with(ejecutivo("clientes.administrar")).contentType(MediaType.APPLICATION_JSON)
                .content(alta("Bruce Wayne", "diana.prince"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("USUARIO_YA_EXISTE"));
        mvc.perform(post("/api/v1/clientes").with(cliente("diana.prince", 4, DIANA, "clientes.administrar"))
                .contentType(MediaType.APPLICATION_JSON).content(alta("Bruce Wayne", "bruce.wayne")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/clientes").with(ejecutivo("clientes.administrar")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"nombre\":\"Bruce Wayne\",\"edad\":10,\"usuario\":\"B!\",\"claveInicial\":\"corta\",\"perfil\":{}}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errores.length()").value(3));
    }

    @Test
    @DisplayName("Sin token: 401 NO_AUTENTICADO; token ilegible: 401 TOKEN_INVALIDO; ambos con el ProblemDetail comun")
    void sinTokenOTokenInvalido() throws Exception {
        mvc.perform(get("/api/v1/clientes/" + DIANA)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"))
                .andExpect(jsonPath("$.type").value("urn:bancoxyz:problema:no-autenticado"));
        mvc.perform(get("/api/v1/clientes/" + DIANA).header("Authorization", "Bearer esto.no.es-un-jwt"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.codigo").value("TOKEN_INVALIDO"))
                .andExpect(jsonPath("$.correlacionId").isNotEmpty());
    }

    @Test
    @DisplayName("cuentas-service (token de servicio con su certificado) lee la ficha de cualquier cliente para una apertura")
    void tokenDeServicio() throws Exception {
        var token = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
                .jwt(j -> j.subject("cuentas-service").claim("scope", java.util.List.of("clientes.leer")))
                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_clientes.leer"));
        var propio = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                .x509(PkiDePrueba.certificado("cuentas-service"));
        var ajeno = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                .x509(PkiDePrueba.certificado("bff-web"));
        mvc.perform(get("/api/v1/clientes/" + DIANA).with(s -> propio.postProcessRequest(token.postProcessRequest(s))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nombre").value("Diana Prince"));
        mvc.perform(get("/api/v1/clientes/" + DIANA).with(s -> ajeno.postProcessRequest(token.postProcessRequest(s))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.codigo").value("CERTIFICADO_NO_CORRESPONDE"));
    }
}
