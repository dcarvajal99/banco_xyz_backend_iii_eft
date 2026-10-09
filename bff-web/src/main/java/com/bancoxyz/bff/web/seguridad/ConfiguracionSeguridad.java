package com.bancoxyz.bff.web.seguridad;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.web.PathPatternRequestMatcherBuilderFactoryBean;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * Seguridad del canal navegador: sesion de servidor + cookie HttpOnly + CSRF.
 *
 * <p>Por que no JWT en el navegador: cualquier token que JavaScript pueda leer lo roba un XSS. Aqui la identidad
 * queda en el servidor y el navegador solo tiene una cookie HttpOnly. Es el uso del BFF que recomienda el borrador
 * IETF "OAuth 2.0 for Browser-Based Applications".</p>
 */
@Configuration
public class ConfiguracionSeguridad {

    @Bean
    public PathPatternRequestMatcherBuilderFactoryBean constructorDeRutas() {
        return new PathPatternRequestMatcherBuilderFactoryBean();
    }

    /** El mismo repositorio lo usan la cadena y el login manual: si fueran distintos, la sesion no se reconoceria. */
    @Bean
    public SecurityContextRepository repositorioDeContexto() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public SecurityFilterChain cadenaWeb(HttpSecurity http, SecurityContextRepository repositorioDeContexto,
                                         ObjectMapper json) throws Exception {
        ErroresDeSeguridadWeb errores = new ErroresDeSeguridadWeb(json);
        CookieCsrfTokenRepository repositorioCsrf = CookieCsrfTokenRepository.withHttpOnlyFalse();
        // Secure explicito: no depende de que el proxy de delante informe bien el esquema.
        repositorioCsrf.setCookieCustomizer(cookie -> cookie.sameSite("Strict").secure(true));
        http
                .securityContext(c -> c.securityContextRepository(repositorioDeContexto))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(repositorioCsrf)
                        // Sin XOR: una SPA lee la cookie XSRF-TOKEN y la reenvia tal cual en X-XSRF-TOKEN.
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        // Exento SOLO el POST del login y con metodo explicito: el DELETE de la misma ruta (logout) SI
                        // exige CSRF. El login acepta solo application/json, que un formulario de otro sitio no puede
                        // enviar sin preflight CORS, y este BFF no habilita CORS.
                        .ignoringRequestMatchers(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/web/api/sesion")))
                .authorizeHttpRequests(a -> a
                        // Las metricas solo existen en el puerto de operacion (127.0.0.1, perfil tls).
                        .requestMatchers("/actuator/health", "/actuator/info", "/actuator/circuitbreakers",
                                "/actuator/circuitbreakerevents", "/actuator/metrics", "/actuator/metrics/**", "/v3/api-docs/**",
                                "/swagger-ui/**", "/swagger-ui.html", "/error").permitAll()
                        .requestMatchers(HttpMethod.POST, "/web/api/sesion").permitAll()
                        .requestMatchers(HttpMethod.GET, "/web/api/csrf").permitAll()
                        .requestMatchers("/web/api/ejecutivo/**").hasRole(UsuarioWeb.EJECUTIVO)
                        .requestMatchers("/web/api/**").authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(e -> e.authenticationEntryPoint(errores).accessDeniedHandler(errores))
                // Sin request cache: si no, cada 401 guardaria la peticion en una sesion nueva y mandaria Set-Cookie.
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .headers(h -> h
                        // Igual en el core y en los tres BFF. HSTS solo se escribe en respuestas HTTPS.
                        .httpStrictTransportSecurity(hsts -> hsts.maxAgeInSeconds(31_536_000).includeSubDomains(true))
                        .referrerPolicy(r -> r.policy(ReferrerPolicy.NO_REFERRER))
                        .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
                                PathPatternRequestMatcher.withDefaults().matcher("/web/api/**"),
                                new StaticHeadersWriter("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'"))));
        return http.build();
    }
}
