package com.bancoxyz.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import com.bancoxyz.auth.oauth.UsuarioDelBanco;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;

/** Un cliente OAuth por canal: web confidencial, movil publico con PKCE, y scopes recortados segun el rol. */
@SpringBootTest
@ActiveProfiles("prueba")
@AutoConfigureMockMvc
class CanalesOAuthTest extends PruebaOAuth {

    private static final String MOVIL = "http://127.0.0.1:8099/callback";
    private static final String TODOS = "openid cuentas.leer cuentas.administrar clientes.leer clientes.perfil "
            + "clientes.administrar pagos.escribir pagos.leer reportes.leer";

    private static UsernamePasswordAuthenticationToken ejecutivo() {
        return UsernamePasswordAuthenticationToken.authenticated(new UsuarioDelBanco("ejecutivo", 8L, null, "EJECUTIVO"),
                null, List.of(new SimpleGrantedAuthority("ROLE_EJECUTIVO")));
    }

    private List<String> scopesDelToken(UsernamePasswordAuthenticationToken quien) throws Exception {
        String verificador = verificador();
        String redireccion = mvc.perform(get("/oauth2/authorize").queryParam("response_type", "code")
                        .queryParam("client_id", "bff-web").queryParam("redirect_uri", REDIRECCION).queryParam("scope", TODOS)
                        .queryParam("state", "e").queryParam("code_challenge", desafio(verificador))
                        .queryParam("code_challenge_method", "S256").with(authentication(quien)))
                .andReturn().getResponse().getRedirectedUrl();
        String token = cuerpo(canjear(parametro(redireccion, "code"), verificador)).get("access_token").asText();
        @SuppressWarnings("unchecked")
        List<String> scopes = (List<String>) SignedJWT.parse(token).getJWTClaimsSet().getClaim("scope");
        return scopes;
    }

    @Test
    @DisplayName("Minimo privilegio por rol: un cliente no recibe scopes de administracion; un ejecutivo no puede pagar")
    void scopesPorRol() throws Exception {
        assertThat(scopesDelToken(diana())).contains("pagos.escribir", "clientes.perfil")
                .doesNotContain("cuentas.administrar", "clientes.administrar", "reportes.leer");
        assertThat(scopesDelToken(ejecutivo())).contains("cuentas.administrar", "reportes.leer", "clientes.administrar")
                .doesNotContain("pagos.escribir", "clientes.perfil");
    }

    @Test
    @DisplayName("App movil: cliente publico, canjea el codigo sin secreto pero con el verificador PKCE")
    void movilPublicoConPkce() throws Exception {
        String verificador = verificador();
        String redireccion = mvc.perform(get("/oauth2/authorize").queryParam("response_type", "code")
                        .queryParam("client_id", "banca-movil").queryParam("redirect_uri", MOVIL)
                        .queryParam("scope", "openid cuentas.leer pagos.escribir").queryParam("state", "m")
                        .queryParam("code_challenge", desafio(verificador)).queryParam("code_challenge_method", "S256")
                        .with(authentication(diana())))
                .andReturn().getResponse().getRedirectedUrl();
        Map<String, Object> claims = SignedJWT.parse(cuerpo(mvc.perform(post("/oauth2/token")
                        .param("grant_type", "authorization_code").param("client_id", "banca-movil")
                        .param("code", parametro(redireccion, "code")).param("redirect_uri", MOVIL)
                        .param("code_verifier", verificador)).andExpect(status().isOk()))
                .get("access_token").asText()).getJWTClaimsSet().getClaims();
        assertThat(claims).containsEntry("cliente_id", 1L).containsEntry("rol", "CLIENTE").containsEntry("azp", "banca-movil");
    }

    @Test
    @DisplayName("Un ejecutivo no obtiene codigo para la app movil (access_denied); ni la app pide scopes de administracion")
    void ejecutivoFueraDelMovil() throws Exception {
        String redireccion = mvc.perform(get("/oauth2/authorize").queryParam("response_type", "code")
                        .queryParam("client_id", "banca-movil").queryParam("redirect_uri", MOVIL)
                        .queryParam("scope", "openid cuentas.leer").queryParam("state", "m")
                        .queryParam("code_challenge", desafio(verificador())).queryParam("code_challenge_method", "S256")
                        .with(authentication(ejecutivo())))
                .andReturn().getResponse().getRedirectedUrl();
        assertThat(parametro(redireccion, "error")).isEqualTo("access_denied");
        String admin = mvc.perform(get("/oauth2/authorize").queryParam("response_type", "code")
                        .queryParam("client_id", "banca-movil").queryParam("redirect_uri", MOVIL)
                        .queryParam("scope", "reportes.leer").queryParam("state", "m")
                        .queryParam("code_challenge", desafio(verificador())).queryParam("code_challenge_method", "S256")
                        .with(authentication(diana())))
                .andReturn().getResponse().getRedirectedUrl();
        assertThat(parametro(admin, "error")).isEqualTo("invalid_scope");
    }
}
