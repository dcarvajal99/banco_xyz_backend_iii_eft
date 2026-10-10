package com.bancoxyz.bff.cajero.seguridad;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.bancoxyz.bff.cajero.config.PropiedadesCajero;
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
 * Cadena de seguridad del canal cajero, en orden: certificado de cliente del terminal (mTLS), firma HMAC de la
 * solicitud, token de sesion de la tarjeta y la verificacion de que la sesion pertenece a ese terminal.
 */
@Configuration
public class ConfiguracionSeguridad {

    @Bean
    public PathPatternRequestMatcherBuilderFactoryBean constructorDeRutas() {
        return new PathPatternRequestMatcherBuilderFactoryBean();
    }

    /** Falla al arrancar con un secreto corto: Nimbus solo lo notaria al emitir el primer token. */
    @Bean
    public SecretKey claveDeSesiones(PropiedadesCajero propiedades) {
        byte[] bytes = propiedades.jwt().secreto().getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("banco.cajero.jwt.secreto debe tener al menos 32 bytes para HS256");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    public JwtEncoder codificadorDeSesiones(SecretKey claveDeSesiones) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(claveDeSesiones));
    }

    /**
     * Tolerancia CERO al vencimiento: el mismo proceso emite y valida, no hay relojes desfasados que
     * compensar. El validador por defecto tolera 60 s y convertiria una sesion de 2 minutos en una de 3.
     */
    @Bean
    public JwtDecoder decodificadorDeSesiones(SecretKey claveDeSesiones) {
        NimbusJwtDecoder decodificador = NimbusJwtDecoder.withSecretKey(claveDeSesiones)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decodificador.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(Duration.ZERO),
                new JwtIssuerValidator(EmisorDeSesiones.EMISOR),
                new JwtAudienceValidator(EmisorDeSesiones.AUDIENCIA)));
        return decodificador;
    }

    @Bean
    public RespuestasDeSeguridad respuestasDeSeguridad(ObjectMapper json) {
        return new RespuestasDeSeguridad(json);
    }

    @Bean
    public SecurityFilterChain cadenaDelCajero(HttpSecurity http, JwtDecoder decodificadorDeSesiones,
                                               RespuestasDeSeguridad respuestas, PropiedadesCajero propiedades,
                                               RegistroDeRevocaciones revocaciones, Clock reloj) throws Exception {
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
                                PathPatternRequestMatcher.withDefaults().matcher("/cajero/v1/**"),
                                new StaticHeadersWriter("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'"))))
                .authorizeHttpRequests(a -> a
                        // Actuator: solo en el puerto de operacion, que no sale de la red interna. Lectura libre (salud,
                        // metricas, circuitos); cualquier escritura exige autenticacion.
                        .requestMatchers(HttpMethod.GET, "/actuator/**").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/error").permitAll()
                        .requestMatchers(HttpMethod.POST, "/cajero/v1/sesiones").permitAll()
                        .requestMatchers("/cajero/v1/**").authenticated()
                        .anyRequest().denyAll())
                // El entry point va en los dos lugares: token invalido y token ausente salen por caminos distintos.
                .oauth2ResourceServer(o -> o.jwt(j -> j.decoder(decodificadorDeSesiones))
                        .authenticationEntryPoint(respuestas)
                        .accessDeniedHandler(respuestas))
                .exceptionHandling(e -> e.authenticationEntryPoint(respuestas).accessDeniedHandler(respuestas))
                .addFilterBefore(new FiltroFirmaDeTerminal(propiedades, reloj, respuestas),
                        BearerTokenAuthenticationFilter.class)
                .addFilterBefore(new FiltroCertificadoDeTerminal(respuestas), FiltroFirmaDeTerminal.class)
                .addFilterAfter(new FiltroSesionDeTerminal(revocaciones, respuestas), BearerTokenAuthenticationFilter.class);
        return http.build();
    }
}
