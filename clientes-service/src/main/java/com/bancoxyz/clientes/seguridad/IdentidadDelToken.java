package com.bancoxyz.clientes.seguridad;

import java.util.Objects;

import com.bancoxyz.clientes.error.Errores;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Quien llama, segun el access token que emitio banco-auth (no segun lo que diga el BFF).
 *
 * <p>Los BFF web y movil reenvian el token del usuario tal cual (token relay). Los claims {@code usuario_id},
 * {@code cliente_id} y {@code rol} los firmo banco-auth, asi que un BFF con un error no puede hacer pasar a un cliente
 * por otro: el servicio decide con el token, no con un encabezado.</p>
 *
 * @param usuario    {@code sub}: nombre de usuario (o id del cliente OAuth en un token de servicio)
 * @param usuarioId  {@code usuario_id}; vacio en un token de servicio
 * @param clienteId  {@code cliente_id}; vacio para un ejecutivo o un servicio
 * @param rol        {@code rol}: CLIENTE o EJECUTIVO; vacio en un token de servicio
 */
public record IdentidadDelToken(String usuario, Long usuarioId, Long clienteId, String rol) {

    public static final String CLIENTE = "CLIENTE";
    public static final String EJECUTIVO = "EJECUTIVO";

    public static IdentidadDelToken de(Authentication autenticacion) {
        if (!(autenticacion instanceof JwtAuthenticationToken token)) {
            throw Errores.accesoDenegado();
        }
        Jwt jwt = token.getToken();
        return new IdentidadDelToken(jwt.getSubject(), numero(jwt.getClaim("usuario_id")),
                numero(jwt.getClaim("cliente_id")), jwt.getClaimAsString("rol"));
    }

    public boolean esEjecutivo() {
        return EJECUTIVO.equals(rol);
    }

    public void exigirEjecutivo() {
        if (!esEjecutivo()) {
            throw Errores.accesoDenegado();
        }
    }

    /** Un cliente solo ve su propia ficha; un ejecutivo, cualquiera. Ajeno o inexistente responden igual (404). */
    public void exigirClienteVisible(long id) {
        if (!esEjecutivo() && !(CLIENTE.equals(rol) && Objects.equals(clienteId, id))) {
            throw Errores.clienteNoEncontrado();
        }
    }

    private static Long numero(Object valor) {
        return valor instanceof Number n ? n.longValue() : null;
    }
}
