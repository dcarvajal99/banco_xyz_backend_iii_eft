package com.bancoxyz.cuentas;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.x509;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import com.bancoxyz.cuentas.cierre.SincronizadorDeCierre;
import com.bancoxyz.cuentas.clientes.ClienteDeClientes;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Base de las pruebas de integracion: H2 en modo PostgreSQL, Flyway real, el esquema migracion que deja el batch y el
 * esquema cuentas re-sincronizado antes de cada prueba. clientes-service se reemplaza por un doble.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("prueba")
@Import(ConfiguracionDePrueba.class)
@Sql(scripts = "/sql/migracion.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
public abstract class PruebaDeCuentas {

    protected static final long DIANA = 4;
    protected static final long JANE = 5;

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected SincronizadorDeCierre sincronizador;

    @Autowired
    protected RelojAjustable reloj;

    @Autowired
    protected ObjectMapper json;

    @MockitoBean
    protected ClienteDeClientes clientes;

    @BeforeEach
    void reiniciarEsquema() {
        reloj.volverAlPresente();
        for (String tabla : List.of("operacion", "pago_aplicado", "evento_procesado", "outbox", "tarjeta", "cuenta")) {
            jdbc.sql("delete from cuentas." + tabla).update();
        }
        sincronizador.sincronizar();
    }

    /** Token de un cliente reenviado por el BFF (con el certificado de ese BFF). */
    protected static RequestPostProcessor cliente(String usuario, long clienteId, String bff, String... scopes) {
        return conCertificado(token(usuario, 10L + clienteId, clienteId, "CLIENTE", scopes), bff);
    }

    protected static RequestPostProcessor ejecutivo(String... scopes) {
        return conCertificado(token("ejecutivo", 99L, null, "EJECUTIVO", scopes), "bff-web");
    }

    /** Token client_credentials de un servicio, con su propio certificado. */
    protected static RequestPostProcessor servicio(String clienteOAuth, String... scopes) {
        return conCertificado(token(clienteOAuth, null, null, null, scopes), clienteOAuth);
    }

    protected static RequestPostProcessor token(String sujeto, Long usuarioId, Long clienteId, String rol, String... scopes) {
        List<String> lista = Arrays.asList(scopes);
        return jwt().jwt(j -> {
                    j.subject(sujeto).claim("scope", lista).audience(List.of("banco-xyz"));
                    if (usuarioId != null) {
                        j.claim("usuario_id", usuarioId).claim("rol", rol);
                    }
                    if (clienteId != null) {
                        j.claim("cliente_id", clienteId);
                    }
                })
                .authorities(lista.stream().map(s -> new SimpleGrantedAuthority("SCOPE_" + s))
                        .toArray(SimpleGrantedAuthority[]::new));
    }

    protected static RequestPostProcessor conCertificado(RequestPostProcessor credencial, String identidad) {
        RequestPostProcessor certificado = x509(PkiDePrueba.certificado(identidad));
        return solicitud -> certificado.postProcessRequest(credencial.postProcessRequest(solicitud));
    }

    protected long tarjetaDeCuenta(long cuentaId) {
        return jdbc.sql("select id from cuentas.tarjeta where cuenta_id = :c").param("c", cuentaId).query(Long.class).single();
    }

    protected String saldo(long cuentaId) {
        return jdbc.sql("select saldo_disponible from cuentas.cuenta where id = :c").param("c", cuentaId)
                .query(BigDecimal.class).single().stripTrailingZeros().toPlainString();
    }
}
