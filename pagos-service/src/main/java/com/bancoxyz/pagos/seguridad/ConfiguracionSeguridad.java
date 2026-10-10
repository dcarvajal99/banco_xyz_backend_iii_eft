package com.bancoxyz.pagos.seguridad;

import com.bancoxyz.pagos.config.PropiedadesPagos;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.config.web.PathPatternRequestMatcherBuilderFactoryBean;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

/**
 * Servidor de recursos OAuth 2.0: token del usuario (reenviado por bff-web o bff-movil, con su certificado) y scope
 * por operacion: {@code pagos.escribir} para solicitar, {@code pagos.leer} para consultar.
 */
@Configuration
public class ConfiguracionSeguridad {

    @Bean
    public PathPatternRequestMatcherBuilderFactoryBean constructorDeRutas() {
        return new PathPatternRequestMatcherBuilderFactoryBean();
    }

    @Bean
    public SecurityFilterChain cadenaDePagos(HttpSecurity http, ProblemasDeSeguridad problemas,
                                             PropiedadesPagos propiedades) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .oauth2ResourceServer(o -> o.authenticationEntryPoint(problemas).accessDeniedHandler(problemas)
                        .jwt(Customizer.withDefaults()))
                .exceptionHandling(e -> e.authenticationEntryPoint(problemas).accessDeniedHandler(problemas))
                .addFilterAfter(new FiltroCertificadoDeLlamante(problemas, propiedades.relevosPermitidos()),
                        BearerTokenAuthenticationFilter.class)
                .headers(h -> h
                        .httpStrictTransportSecurity(hsts -> hsts.maxAgeInSeconds(31_536_000).includeSubDomains(true))
                        .referrerPolicy(r -> r.policy(ReferrerPolicy.NO_REFERRER))
                        .frameOptions(HeadersConfigurer.FrameOptionsConfig::deny))
                .authorizeHttpRequests(a -> a
                        // Actuator: solo en el puerto de operacion, que no sale de la red interna. Lectura libre (salud,
                        // metricas, circuitos); cualquier escritura exige autenticacion.
                        .requestMatchers(HttpMethod.GET, "/actuator/**").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/error").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/pagos/**").hasAuthority("SCOPE_pagos.escribir")
                        .requestMatchers(HttpMethod.GET, "/api/v1/pagos", "/api/v1/pagos/*").hasAuthority("SCOPE_pagos.leer")
                        .anyRequest().denyAll());
        return http.build();
    }
}
