package com.bancoxyz.bff.movil.seguridad;

import java.net.MalformedURLException;
import java.net.URI;
import java.time.Duration;
import java.util.List;

import javax.net.ssl.SSLSocketFactory;

import com.bancoxyz.bff.movil.config.PropiedadesJwt;
import com.bancoxyz.bff.movil.config.PropiedadesMovil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.springframework.boot.ssl.NoSuchSslBundleException;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.config.web.PathPatternRequestMatcherBuilderFactoryBean;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

/**
 * La app movil es un cliente PUBLICO de banco-auth (authorization_code + PKCE): no guarda secretos y presenta aqui el
 * access token del usuario. El BFF lo valida contra el JWK Set (firma, emisor, audiencia, vencimiento) y exige que haya
 * sido emitido para la app ({@code azp=banca-movil}): un token de la banca web no abre la API movil. Despues lo reenvia
 * a los microservicios (token relay), que vuelven a validarlo.
 */
@Configuration
public class ConfiguracionSeguridad {

    @Bean
    public PathPatternRequestMatcherBuilderFactoryBean constructorDeRutas() {
        return new PathPatternRequestMatcherBuilderFactoryBean();
    }

    @Bean
    public ErroresDeSeguridadMovil erroresDeSeguridadMovil(ObjectMapper json) {
        return new ErroresDeSeguridadMovil(json);
    }

    @Bean
    public JwtDecoder jwtDecoder(PropiedadesJwt jwt, PropiedadesMovil movil, SslBundles bundles) throws MalformedURLException {
        DefaultResourceRetriever lector = new DefaultResourceRetriever(2000, 2000, 51_200, true, socketsTls(bundles));
        JWKSource<SecurityContext> claves = JWKSourceBuilder.create(URI.create(jwt.jwkSetUri()).toURL(), lector)
                .cache(jwt.cacheJwks().toMillis(), 2000).refreshAheadCache(false).rateLimited(false).build();
        DefaultJWTProcessor<SecurityContext> procesador = new DefaultJWTProcessor<>();
        procesador.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, claves));
        procesador.setJWTClaimsSetVerifier((claims, contexto) -> { });
        NimbusJwtDecoder decodificador = new NimbusJwtDecoder(procesador);
        decodificador.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(Duration.ZERO),
                new JwtIssuerValidator(jwt.emisor()), claim("aud", jwt.audiencia()), claim("azp", movil.clienteOAuth())));
        return decodificador;
    }

    @Bean
    public SecurityFilterChain cadenaMovil(HttpSecurity http, ErroresDeSeguridadMovil errores) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .headers(h -> h.httpStrictTransportSecurity(hsts -> hsts.maxAgeInSeconds(31_536_000).includeSubDomains(true))
                        .referrerPolicy(r -> r.policy(ReferrerPolicy.NO_REFERRER)))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info", "/actuator/prometheus",
                                "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/error").permitAll()
                        .requestMatchers(HttpMethod.GET, "/movil/v1/inicio", "/movil/v1/cuentas/*/movimientos")
                        .hasAuthority("SCOPE_cuentas.leer")
                        .requestMatchers(HttpMethod.POST, "/movil/v1/transferencias").hasAuthority("SCOPE_pagos.escribir")
                        .requestMatchers(HttpMethod.GET, "/movil/v1/pagos/*").hasAuthority("SCOPE_pagos.leer")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(j -> { }).authenticationEntryPoint(errores).accessDeniedHandler(errores))
                .exceptionHandling(e -> e.authenticationEntryPoint(errores).accessDeniedHandler(errores));
        return http.build();
    }

    /** El claim (texto o lista) debe contener el valor esperado. */
    static OAuth2TokenValidator<Jwt> claim(String nombre, String esperado) {
        return token -> {
            Object valor = token.getClaim(nombre);
            boolean ok = valor instanceof List<?> lista ? lista.contains(esperado) : esperado.equals(valor);
            return ok ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Token sin " + nombre + "=" + esperado, null));
        };
    }

    private static SSLSocketFactory socketsTls(SslBundles bundles) {
        try {
            return bundles.getBundle("confianza").createSslContext().getSocketFactory();
        } catch (NoSuchSslBundleException sinPerfilTls) {
            return (SSLSocketFactory) SSLSocketFactory.getDefault();
        }
    }
}
