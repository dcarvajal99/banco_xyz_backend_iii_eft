package com.bancoxyz.bff.movil.seguridad;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.bancoxyz.bff.movil.config.PropiedadesMovil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.config.web.PathPatternRequestMatcherBuilderFactoryBean;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * Seguridad del canal movil: sin sesion en el servidor (millones de telefonos no caben en memoria), token corto
 * con emisor, audiencia y secreto propios del canal.
 */
@Configuration
public class ConfiguracionSeguridad {

    @Bean
    public PathPatternRequestMatcherBuilderFactoryBean constructorDeRutas() {
        return new PathPatternRequestMatcherBuilderFactoryBean();
    }

    @Bean
    public SecretKey claveMovil(PropiedadesMovil propiedades) {
        byte[] bytes = propiedades.jwt().secreto().getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("banco.movil.jwt.secreto debe tener al menos 32 bytes para HS256");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    public JwtEncoder codificadorMovil(SecretKey claveMovil) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(claveMovil));
    }

    /** Tolerancia cero: el validador por defecto acepta tokens vencidos hasta 60 s. */
    @Bean
    public JwtDecoder decodificadorMovil(SecretKey claveMovil) {
        NimbusJwtDecoder decodificador = NimbusJwtDecoder.withSecretKey(claveMovil).macAlgorithm(MacAlgorithm.HS256).build();
        decodificador.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(Duration.ZERO),
                new JwtIssuerValidator(EmisorDeTokens.EMISOR),
                new JwtAudienceValidator(EmisorDeTokens.AUDIENCIA)));
        return decodificador;
    }

    @Bean
    public ErroresDeSeguridadMovil erroresDeSeguridadMovil(ObjectMapper json) {
        return new ErroresDeSeguridadMovil(json);
    }

    @Bean
    public SecurityFilterChain cadenaMovil(HttpSecurity http, JwtDecoder decodificadorMovil, ErroresDeSeguridadMovil errores,
                                           AlmacenDeRefresh refresh) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .headers(h -> h
                        // Igual en el core y en los tres BFF. HSTS solo se escribe en respuestas HTTPS.
                        .httpStrictTransportSecurity(hsts -> hsts.maxAgeInSeconds(31_536_000).includeSubDomains(true))
                        .referrerPolicy(r -> r.policy(ReferrerPolicy.NO_REFERRER))
                        .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
                                PathPatternRequestMatcher.withDefaults().matcher("/movil/v1/**"),
                                new StaticHeadersWriter("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'"))))
                .authorizeHttpRequests(a -> a
                        // Las metricas solo existen en el puerto de operacion (127.0.0.1, perfil tls).
                        .requestMatchers("/actuator/health", "/actuator/info", "/actuator/circuitbreakers",
                                "/actuator/circuitbreakerevents", "/actuator/metrics", "/actuator/metrics/**", "/v3/api-docs/**",
                                "/swagger-ui/**", "/swagger-ui.html", "/error").permitAll()
                        .requestMatchers(HttpMethod.POST, "/movil/v1/sesiones", "/movil/v1/sesiones/renovar").permitAll()
                        .requestMatchers("/movil/v1/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(j -> j.decoder(decodificadorMovil))
                        .authenticationEntryPoint(errores)
                        .accessDeniedHandler(errores))
                .exceptionHandling(e -> e.authenticationEntryPoint(errores).accessDeniedHandler(errores))
                .addFilterAfter(new FiltroDispositivo(refresh, errores), BearerTokenAuthenticationFilter.class);
        return http.build();
    }
}
