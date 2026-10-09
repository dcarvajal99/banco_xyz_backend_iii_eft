package com.bancoxyz.clientes;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.x509;

import java.util.Arrays;
import java.util.List;

import com.bancoxyz.clientes.sincronizacion.SincronizadorDeClientes;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Base de las pruebas de integracion: H2 en modo PostgreSQL, Flyway real, el maestro de clientes que dejaria el batch y
 * el esquema clientes re-sincronizado antes de cada prueba (con los usuarios de demostracion).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("prueba")
@Import(ConfiguracionDePrueba.class)
@Sql(scripts = "/sql/migracion.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
public abstract class PruebaDeClientes {

    protected static final long ALICE = 1;
    protected static final long DIANA = 4;
    protected static final long STEVE = 7;

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected SincronizadorDeClientes sincronizador;

    @Autowired
    protected RelojAjustable reloj;

    @Autowired
    protected ObjectMapper json;

    @BeforeEach
    void reiniciarEsquema() {
        reloj.volverAlPresente();
        jdbc.sql("delete from clientes.outbox").update();
        jdbc.sql("delete from clientes.usuario").update();
        jdbc.sql("delete from clientes.cliente").update();
        sincronizador.sincronizar();
    }

    /** banco-auth: su clave de servicio y el certificado de su conexion mTLS. */
    protected static RequestPostProcessor bancoAuth() {
        return servicio("banco-auth", "autenticacion-secreto-dev", "banco-auth");
    }

    protected static RequestPostProcessor servicio(String usuario, String clave, String certificadoDe) {
        RequestPostProcessor credencial = httpBasic(usuario, clave);
        RequestPostProcessor certificado = x509(PkiDePrueba.certificado(certificadoDe));
        return solicitud -> certificado.postProcessRequest(credencial.postProcessRequest(solicitud));
    }

    /** Access token de un cliente, ya validado (firma, emisor, audiencia), con sus scopes. */
    protected static RequestPostProcessor cliente(String usuario, long usuarioId, long clienteId, String... scopes) {
        return token(usuario, usuarioId, clienteId, "CLIENTE", scopes);
    }

    protected static RequestPostProcessor ejecutivo(String... scopes) {
        return token("ejecutivo", 99, null, "EJECUTIVO", scopes);
    }

    private static RequestPostProcessor token(String usuario, long usuarioId, Long clienteId, String rol, String... scopes) {
        List<String> lista = Arrays.asList(scopes);
        return jwt().jwt(j -> {
                    j.subject(usuario).claim("usuario_id", usuarioId).claim("rol", rol).claim("scope", lista)
                            .audience(List.of("banco-xyz"));
                    if (clienteId != null) {
                        j.claim("cliente_id", clienteId);
                    }
                })
                .authorities(lista.stream().map(s -> new SimpleGrantedAuthority("SCOPE_" + s))
                        .toArray(SimpleGrantedAuthority[]::new));
    }

    protected long usuarioId(String usuario) {
        return jdbc.sql("select id from clientes.usuario where usuario = :u").param("u", usuario).query(Long.class).single();
    }
}
