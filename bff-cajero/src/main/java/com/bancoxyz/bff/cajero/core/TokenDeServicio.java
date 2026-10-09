package com.bancoxyz.bff.cajero.core;

import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.endpoint.RestClientClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.web.client.RestClient;

/**
 * Token propio de bff-cajero (client_credentials, scope cajero.operar) para llamar a cuentas-service.
 * Se pide una vez y se reutiliza hasta 30 s antes de vencer; el endpoint de tokens se llama sin balanceo y con el
 * bundle TLS del servicio.
 */
@Configuration(proxyBeanMethods = false)
public class TokenDeServicio {

    /** Id del registro OAuth de cuentas-service en {@code spring.security.oauth2.client.registration.*}. */
    static final String REGISTRO = "cuentas";

    private static final Authentication SERVICIO = UsernamePasswordAuthenticationToken.authenticated(
            "bff-cajero", null, List.of());

    @Bean
    OAuth2AuthorizedClientManager gestorDeTokens(ClientRegistrationRepository registros, ObjectProvider<SslBundles> bundles,
                                                 @Value("${spring.http.client.ssl.bundle:}") String bundle) {
        ClientHttpRequestFactorySettings ajustes = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(Duration.ofSeconds(1)).withReadTimeout(Duration.ofSeconds(3));
        if (!bundle.isBlank()) {
            ajustes = ajustes.withSslBundle(bundles.getObject().getBundle(bundle));
        }
        RestClientClientCredentialsTokenResponseClient pedidor = new RestClientClientCredentialsTokenResponseClient();
        pedidor.setRestClient(RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(ajustes))
                .messageConverters(c -> {
                    c.clear();
                    c.add(new FormHttpMessageConverter());
                    c.add(new OAuth2AccessTokenResponseHttpMessageConverter());
                })
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                .build());
        AuthorizedClientServiceOAuth2AuthorizedClientManager gestor = new AuthorizedClientServiceOAuth2AuthorizedClientManager(
                registros, new InMemoryOAuth2AuthorizedClientService(registros));
        gestor.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder()
                .clientCredentials(c -> c.accessTokenResponseClient(pedidor).clockSkew(Duration.ofSeconds(30)))
                .build());
        return gestor;
    }

    /** Agrega {@code Authorization: Bearer <token de servicio>} a cada llamada a cuentas-service. */
    public static OAuth2ClientHttpRequestInterceptor interceptor(OAuth2AuthorizedClientManager gestor) {
        OAuth2ClientHttpRequestInterceptor interceptor = new OAuth2ClientHttpRequestInterceptor(gestor);
        interceptor.setClientRegistrationIdResolver(solicitud -> REGISTRO);
        interceptor.setPrincipalResolver(solicitud -> SERVICIO);
        return interceptor;
    }
}
