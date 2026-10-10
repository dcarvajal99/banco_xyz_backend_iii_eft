package com.bancoxyz.clientes.seguridad;

import com.bancoxyz.clientes.config.PropiedadesClientes;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.config.web.PathPatternRequestMatcherBuilderFactoryBean;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * Dos puertas, dos credenciales.
 *
 * <ol>
 *   <li><b>{@code /api/v1/autenticacion/**}</b>: solo banco-auth, con clave Basic y su certificado de cliente (mTLS).
 *       Es el servidor de autorizacion: verifica la clave del usuario aqui ANTES de que exista un token, asi que no
 *       puede presentarse con uno.</li>
 *   <li><b>Todo lo demas</b>: servidor de recursos OAuth 2.0. Un JWT de banco-auth (firma RS256 contra su JWK Set,
 *       emisor, audiencia {@code banco-xyz}, vencimiento) y el scope de cada operacion; el rol y el cliente salen de
 *       los claims del mismo token ({@link IdentidadDelToken}); el certificado del llamante amarrado al token
 *       ({@link FiltroCertificadoDeLlamante}).</li>
 * </ol>
 */
@Configuration
public class ConfiguracionSeguridad {

    static final String ROL_AUTENTICACION = "SERVICIO_AUTENTICACION";

    /** Hace que requestMatchers(String) use PathPattern, el comportamiento de Spring Security 7. */
    @Bean
    public PathPatternRequestMatcherBuilderFactoryBean constructorDeRutas() {
        return new PathPatternRequestMatcherBuilderFactoryBean();
    }

    /** BCrypt de costo 4 para la clave de servicio: es larga y aleatoria, no necesita un hash lento. */
    @Bean
    public PasswordEncoder codificadorDeServicios() {
        return new BCryptPasswordEncoder(4);
    }

    @Bean
    public UserDetailsService servicios(PasswordEncoder codificadorDeServicios, PropiedadesClientes propiedades) {
        return new InMemoryUserDetailsManager(User.withUsername(propiedades.canalAutenticacion().usuario())
                .password(codificadorDeServicios.encode(propiedades.canalAutenticacion().clave()))
                .roles(ROL_AUTENTICACION).build());
    }

    @Bean
    @Order(1)
    public SecurityFilterChain cadenaDeAutenticacion(HttpSecurity http, ProblemasDeSeguridad problemas) throws Exception {
        comun(http.securityMatcher("/api/v1/autenticacion/**"), problemas)
                // El entry point va en httpBasic Y en exceptionHandling: clave mala y credencial ausente salen por
                // caminos distintos y los dos deben responder el mismo ProblemDetail.
                .httpBasic(basic -> basic.authenticationEntryPoint(problemas))
                .addFilterAfter(new FiltroCertificadoDeServicio(problemas), BasicAuthenticationFilter.class)
                .authorizeHttpRequests(a -> a.anyRequest().hasRole(ROL_AUTENTICACION));
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain cadenaDeClientes(HttpSecurity http, ProblemasDeSeguridad problemas,
                                                PropiedadesClientes propiedades) throws Exception {
        comun(http, problemas)
                .oauth2ResourceServer(o -> o.authenticationEntryPoint(problemas).accessDeniedHandler(problemas)
                        .jwt(Customizer.withDefaults()))
                .addFilterAfter(new FiltroCertificadoDeLlamante(problemas, propiedades.relevosPermitidos()),
                        BearerTokenAuthenticationFilter.class)
                .authorizeHttpRequests(a -> a
                        // Actuator: solo en el puerto de operacion, que no sale de la red interna. Lectura libre (salud,
                        // metricas, circuitos); cualquier escritura exige autenticacion.
                        .requestMatchers(HttpMethod.GET, "/actuator/**").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/error").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/clientes", "/api/v1/clientes/*", "/api/v1/clientes/*/avisos")
                        .hasAuthority("SCOPE_clientes.leer")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/clientes/*/perfil").hasAuthority("SCOPE_clientes.perfil")
                        .requestMatchers(HttpMethod.POST, "/api/v1/clientes").hasAuthority("SCOPE_clientes.administrar")
                        .anyRequest().denyAll());
        return http.build();
    }

    /** Lo que comparten las dos cadenas: sin sesion, sin formulario, errores como ProblemDetail y encabezados seguros. */
    private static HttpSecurity comun(HttpSecurity http, ProblemasDeSeguridad problemas) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .exceptionHandling(e -> e.authenticationEntryPoint(problemas).accessDeniedHandler(problemas))
                .headers(h -> h
                        .httpStrictTransportSecurity(hsts -> hsts.maxAgeInSeconds(31_536_000).includeSubDomains(true))
                        .referrerPolicy(r -> r.policy(ReferrerPolicy.NO_REFERRER))
                        .frameOptions(HeadersConfigurer.FrameOptionsConfig::deny)
                        .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
                                PathPatternRequestMatcher.withDefaults().matcher("/api/**"),
                                new StaticHeadersWriter(
                                        "Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'"))));
    }
}
