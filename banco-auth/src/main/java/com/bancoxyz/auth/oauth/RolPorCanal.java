package com.bancoxyz.auth.oauth;

import java.util.List;
import java.util.function.Consumer;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationValidator;

/**
 * Autorizacion por canal en el endpoint /oauth2/authorize: un EJECUTIVO no puede obtener un codigo para la app movil
 * (la consola del ejecutivo existe solo en la web). Se suma a las validaciones estandar (redirect_uri y scopes).
 */
final class RolPorCanal {

    static final String CLIENTE_MOVIL = "banca-movil";

    private RolPorCanal() {
    }

    static void aplicar(List<AuthenticationProvider> proveedores) {
        proveedores.stream().filter(OAuth2AuthorizationCodeRequestAuthenticationProvider.class::isInstance)
                .map(OAuth2AuthorizationCodeRequestAuthenticationProvider.class::cast)
                .forEach(p -> p.setAuthenticationValidator(
                        new OAuth2AuthorizationCodeRequestAuthenticationValidator().andThen(soloClientesEnMovil())));
    }

    private static Consumer<OAuth2AuthorizationCodeRequestAuthenticationContext> soloClientesEnMovil() {
        return contexto -> {
            OAuth2AuthorizationCodeRequestAuthenticationToken solicitud = contexto.getAuthentication();
            if (!CLIENTE_MOVIL.equals(contexto.getRegisteredClient().getClientId())
                    || !(solicitud.getPrincipal() instanceof Authentication usuario) || !usuario.isAuthenticated()) {
                return;
            }
            boolean ejecutivo = usuario.getAuthorities().stream().anyMatch(a -> "ROLE_EJECUTIVO".equals(a.getAuthority()));
            if (ejecutivo) {
                throw new OAuth2AuthorizationCodeRequestAuthenticationException(new OAuth2Error(OAuth2ErrorCodes.ACCESS_DENIED,
                        "La banca movil es solo para clientes", null), solicitud);
            }
        };
    }
}
