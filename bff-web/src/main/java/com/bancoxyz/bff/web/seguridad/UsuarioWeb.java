package com.bancoxyz.bff.web.seguridad;

import com.bancoxyz.bff.web.error.ManejadorDeErrores;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/** Quien tiene la sesion, segun el id_token que firmo banco-auth (rol y cliente del banco). */
public record UsuarioWeb(String usuario, Long usuarioId, Long clienteId, String rol, String origen) {

    public static UsuarioWeb de(Authentication autenticacion) {
        OidcUser oidc = (OidcUser) autenticacion.getPrincipal();
        return new UsuarioWeb(oidc.getSubject(), numero(oidc.getClaim("usuario_id")), numero(oidc.getClaim("cliente_id")),
                oidc.getClaimAsString("rol"), oidc.getClaimAsString("origen"));
    }

    public boolean esEjecutivo() {
        return "EJECUTIVO".equals(rol);
    }

    public void exigirEjecutivo() {
        if (!esEjecutivo()) {
            throw new ManejadorDeErrores.AccesoDeEjecutivo();
        }
    }

    private static Long numero(Object valor) {
        return valor instanceof Number n ? n.longValue() : null;
    }
}
