package com.bancoxyz.cuentas.seguridad;

import java.util.List;

import com.bancoxyz.cuentas.error.Errores;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Quien llama, segun el access token que emitio banco-auth.
 *
 * <ul>
 *   <li><b>Token de usuario</b> (lo reenvian los BFF web y movil): trae {@code usuario_id}, {@code cliente_id} y
 *       {@code rol}, firmados por banco-auth. El servicio decide con ellos que cuentas puede ver; no confia en un
 *       encabezado que ponga el BFF.</li>
 *   <li><b>Token de servicio</b> (client_credentials de bff-cajero o pagos-service): sin usuario; {@code sub} es el id
 *       del cliente OAuth. Lo que puede hacer lo dicen sus scopes.</li>
 * </ul>
 */
public record IdentidadDelToken(String sujeto, Long usuarioId, Long clienteId, String rol, List<String> scopes) {

    public static final String CLIENTE = "CLIENTE";
    public static final String EJECUTIVO = "EJECUTIVO";

    public static IdentidadDelToken de(Authentication autenticacion) {
        if (!(autenticacion instanceof JwtAuthenticationToken token)) {
            throw Errores.accesoDenegado();
        }
        return de(token.getToken());
    }

    public static IdentidadDelToken de(Jwt jwt) {
        List<String> scopes = jwt.getClaimAsStringList("scope");
        return new IdentidadDelToken(jwt.getSubject(), numero(jwt.getClaim("usuario_id")),
                numero(jwt.getClaim("cliente_id")), jwt.getClaimAsString("rol"), scopes == null ? List.of() : scopes);
    }

    /** Token de un usuario (cliente o ejecutivo) reenviado por un BFF. */
    public boolean esDeUsuario() {
        return usuarioId != null;
    }

    public boolean esEjecutivo() {
        return EJECUTIVO.equals(rol);
    }

    public boolean esCliente() {
        return CLIENTE.equals(rol) && clienteId != null;
    }

    public boolean tieneScope(String scope) {
        return scopes.contains(scope);
    }

    public void exigirEjecutivo() {
        if (!esEjecutivo()) {
            throw Errores.accesoDenegado();
        }
    }

    private static Long numero(Object valor) {
        return valor instanceof Number n ? n.longValue() : null;
    }
}
