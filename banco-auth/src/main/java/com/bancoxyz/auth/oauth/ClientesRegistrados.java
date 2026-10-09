package com.bancoxyz.auth.oauth;

import java.util.UUID;

import com.bancoxyz.auth.config.PropiedadesAuth;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;

/**
 * Un cliente OAuth por canal y por servicio: cada uno puede pedir solo lo que su canal necesita.
 *
 * <ul>
 *   <li><b>bff-web</b>: confidencial (el secreto vive en el servidor del BFF). authorization_code + PKCE; el navegador
 *       nunca ve el token (queda en la sesion del BFF).</li>
 *   <li><b>banca-movil</b>: publico (una app instalada no guarda secretos). authorization_code + PKCE con redirect a
 *       loopback (RFC 8252); solo clientes y sin scopes de administracion.</li>
 *   <li><b>bff-cajero, pagos-service, cuentas-service</b>: servicios sin usuario, client_credentials y un scope.</li>
 *   <li><b>operacion-banco</b>: rotacion de claves de firma ({@code claves.administrar}).</li>
 * </ul>
 * Los secretos llegan por variable de entorno y se guardan con bcrypt.
 */
@Configuration(proxyBeanMethods = false)
public class ClientesRegistrados {

    @Bean
    public PasswordEncoder codificador() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public RegisteredClientRepository clientes(PropiedadesAuth propiedades, PasswordEncoder codificador) {
        PropiedadesAuth.Cliente web = propiedades.bffWeb();
        RegisteredClient bffWeb = base(web.id())
                .clientSecret(codificador.encode(web.secreto()))
                .clientName("Banca en linea (BFF web)")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUris(uris -> uris.addAll(web.redirectUris()))
                .scope(OidcScopes.OPENID).scope(OidcScopes.PROFILE)
                .scopes(s -> s.addAll(java.util.List.of(Alcances.CUENTAS_LEER, Alcances.CUENTAS_ADMINISTRAR,
                        Alcances.CLIENTES_LEER, Alcances.CLIENTES_PERFIL, Alcances.CLIENTES_ADMINISTRAR,
                        Alcances.PAGOS_ESCRIBIR, Alcances.PAGOS_LEER, Alcances.REPORTES_LEER)))
                .clientSettings(ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(false).build())
                .tokenSettings(tokens(propiedades))
                .build();
        PropiedadesAuth.Cliente movil = propiedades.bancaMovil();
        RegisteredClient bancaMovil = base(movil.id())
                .clientName("Banca movil (app)")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUris(uris -> uris.addAll(movil.redirectUris()))
                .scope(OidcScopes.OPENID)
                .scopes(s -> s.addAll(java.util.List.of(Alcances.CUENTAS_LEER, Alcances.CLIENTES_LEER,
                        Alcances.PAGOS_ESCRIBIR, Alcances.PAGOS_LEER)))
                .clientSettings(ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(false).build())
                .tokenSettings(tokens(propiedades))
                .build();
        return new InMemoryRegisteredClientRepository(bffWeb, bancaMovil,
                servicio(propiedades.bffCajero(), "BFF del cajero automatico", Alcances.CAJERO_OPERAR, propiedades, codificador),
                servicio(propiedades.pagos(), "pagos-service", Alcances.CUENTAS_LEER, propiedades, codificador),
                servicio(propiedades.cuentas(), "cuentas-service", Alcances.CLIENTES_LEER, propiedades, codificador),
                servicio(propiedades.operacion(), "Operacion del banco (claves de firma)", Alcances.CLAVES_ADMINISTRAR,
                        propiedades, codificador));
    }

    private static RegisteredClient.Builder base(String id) {
        return RegisteredClient.withId(UUID.nameUUIDFromBytes(id.getBytes()).toString()).clientId(id);
    }

    /** Access token corto; refresh token que rota en cada uso (el anterior queda invalido). */
    private static TokenSettings tokens(PropiedadesAuth propiedades) {
        return TokenSettings.builder().accessTokenTimeToLive(propiedades.duracionToken())
                .refreshTokenTimeToLive(propiedades.duracionRefresco()).reuseRefreshTokens(false).build();
    }

    /** Cliente sin usuario: solo client_credentials, un scope y un token de vida corta. */
    private static RegisteredClient servicio(PropiedadesAuth.Cliente cliente, String nombre, String alcance,
                                             PropiedadesAuth propiedades, PasswordEncoder codificador) {
        return base(cliente.id())
                .clientSecret(codificador.encode(cliente.secreto()))
                .clientName(nombre)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope(alcance)
                .tokenSettings(TokenSettings.builder().accessTokenTimeToLive(propiedades.duracionToken()).build())
                .build();
    }
}
