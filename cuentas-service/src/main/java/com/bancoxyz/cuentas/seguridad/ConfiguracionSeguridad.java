package com.bancoxyz.cuentas.seguridad;

import com.bancoxyz.cuentas.config.PropiedadesCuentas;
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
 * Servidor de recursos OAuth 2.0: JWT de banco-auth (firma, emisor, audiencia banco-xyz, vencimiento) + scope por
 * operacion + certificado del llamante amarrado al token (FiltroCertificadoDeLlamante).
 *
 * <ul>
 *   <li>{@code cuentas.leer}: consultas (BFF web/movil con el token del usuario; pagos-service con el suyo).</li>
 *   <li>{@code cuentas.administrar}: apertura, mantenimiento y cierre (ademas, rol EJECUTIVO en el token).</li>
 *   <li>{@code cajero.operar}: tarjeta + PIN, saldo y retiro (solo bff-cajero).</li>
 *   <li>{@code reportes.leer}: reportes del cierre (ademas, rol EJECUTIVO).</li>
 * </ul>
 */
@Configuration
public class ConfiguracionSeguridad {

    @Bean
    public PathPatternRequestMatcherBuilderFactoryBean constructorDeRutas() {
        return new PathPatternRequestMatcherBuilderFactoryBean();
    }

    @Bean
    public SecurityFilterChain cadenaDeCuentas(HttpSecurity http, ProblemasDeSeguridad problemas,
                                               PropiedadesCuentas propiedades) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .oauth2ResourceServer(o -> o.authenticationEntryPoint(problemas).accessDeniedHandler(problemas)
                        .jwt(Customizer.withDefaults()))
                .exceptionHandling(e -> e.authenticationEntryPoint(problemas).accessDeniedHandler(problemas))
                .addFilterAfter(new FiltroCertificadoDeLlamante(problemas, propiedades.seguridad().relevosPermitidos()),
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
                        .requestMatchers(HttpMethod.POST, "/api/v1/autenticacion/tarjetas", "/api/v1/cuentas/*/retiros")
                        .hasAuthority("SCOPE_cajero.operar")
                        .requestMatchers(HttpMethod.GET, "/api/v1/cuentas/*")
                        .hasAnyAuthority("SCOPE_cuentas.leer", "SCOPE_cajero.operar")
                        .requestMatchers(HttpMethod.GET, "/api/v1/clientes/*/cuentas", "/api/v1/cuentas/*/movimientos",
                                "/api/v1/cuentas/*/estados-anuales").hasAuthority("SCOPE_cuentas.leer")
                        .requestMatchers(HttpMethod.POST, "/api/v1/cuentas", "/api/v1/cuentas/*/cierre")
                        .hasAuthority("SCOPE_cuentas.administrar")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/cuentas/*").hasAuthority("SCOPE_cuentas.administrar")
                        .requestMatchers(HttpMethod.GET, "/api/v1/reportes/**").hasAuthority("SCOPE_reportes.leer")
                        .requestMatchers(HttpMethod.GET, "/api/v1/cierres/vigentes")
                        .hasAnyAuthority("SCOPE_cuentas.leer", "SCOPE_reportes.leer")
                        .anyRequest().denyAll());
        return http.build();
    }
}
