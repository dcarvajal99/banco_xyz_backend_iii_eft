package com.bancoxyz.bff.web;

import java.time.Instant;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;

/** banco-auth no existe en las pruebas: el access token del usuario de la sesion es fijo. */
@Configuration
public class TokenDePrueba {

    public static final String TOKEN = "token-de-prueba";

    @Bean
    @Primary
    OAuth2AuthorizedClientManager gestorDeTokensDePrueba(ClientRegistrationRepository registros) {
        return solicitud -> new OAuth2AuthorizedClient(registros.findByRegistrationId("banco-auth"), "usuario",
                new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, TOKEN, Instant.now(), Instant.now().plusSeconds(300)));
    }
}
