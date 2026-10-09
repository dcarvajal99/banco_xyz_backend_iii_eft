package com.bancoxyz.bff.web.seguridad;

import java.io.IOException;

import com.bancoxyz.bff.web.error.Problemas;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.web.PathPatternRequestMatcherBuilderFactoryBean;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Seguridad del canal web: patron BFF "token handler".
 *
 * <ul>
 *   <li>El navegador inicia sesion en banco-auth (authorization_code + PKCE, cliente confidencial {@code bff-web}). Los
 *       tokens quedan en la sesion del servidor: el JavaScript nunca los ve, asi que un XSS no puede robarlos.</li>
 *   <li>El navegador solo tiene una cookie de sesion HttpOnly + Secure + SameSite=Lax, y las escrituras exigen el token
 *       CSRF (cookie XSRF-TOKEN leida por la pagina y devuelta en X-XSRF-TOKEN).</li>
 *   <li>Hacia los microservicios el BFF reenvia el access token del usuario (y lo renueva con el refresh token).</li>
 *   <li>La API responde 401 con ProblemDetail (no redirige): la pagina decide mostrar "Ingresar".</li>
 * </ul>
 */
@Configuration
public class ConfiguracionSeguridad {

    @Bean
    public PathPatternRequestMatcherBuilderFactoryBean constructorDeRutas() {
        return new PathPatternRequestMatcherBuilderFactoryBean();
    }

    @Bean
    public SecurityFilterChain cadenaWeb(HttpSecurity http, ClientRegistrationRepository registros, ObjectMapper json)
            throws Exception {
        DefaultOAuth2AuthorizationRequestResolver conPkce = new DefaultOAuth2AuthorizationRequestResolver(registros,
                "/oauth2/authorization");
        conPkce.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        http
                .oauth2Login(o -> o.authorizationEndpoint(a -> a.authorizationRequestResolver(conPkce))
                        .defaultSuccessUrl("/", true))
                .logout(l -> l.logoutRequestMatcher(PathPatternRequestMatcher.withDefaults()
                                .matcher(org.springframework.http.HttpMethod.POST, "/web/api/salir"))
                        .logoutSuccessHandler((req, res, auth) -> res.setStatus(HttpStatus.NO_CONTENT.value()))
                        .deleteCookies("BFF_WEB_SESION"))
                .csrf(c -> c.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .addFilterAfter(new CookieCsrf(), CsrfFilter.class)
                .exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(api401(json),
                        PathPatternRequestMatcher.withDefaults().matcher("/web/api/**")))
                .headers(h -> h
                        .httpStrictTransportSecurity(hsts -> hsts.maxAgeInSeconds(31_536_000).includeSubDomains(true))
                        .contentSecurityPolicy(c -> c.policyDirectives("default-src 'self'; frame-ancestors 'none'")))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/", "/index.html", "/app.js", "/estilos.css", "/favicon.svg", "/error",
                                "/web/api/sesion", "/actuator/health", "/actuator/health/**", "/actuator/info",
                                "/actuator/prometheus", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .anyRequest().authenticated());
        return http.build();
    }

    /** Access token del usuario desde la sesion; si vencio, lo renueva con el refresh token antes de llamar. */
    @Bean
    public OAuth2AuthorizedClientManager gestorDeTokens(ClientRegistrationRepository registros,
                                                        OAuth2AuthorizedClientRepository clientes) {
        DefaultOAuth2AuthorizedClientManager gestor = new DefaultOAuth2AuthorizedClientManager(registros, clientes);
        gestor.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder()
                .authorizationCode().refreshToken().build());
        return gestor;
    }

    private static AuthenticationEntryPoint api401(ObjectMapper json) {
        return (request, response, ex) -> {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            json.writeValue(response.getOutputStream(), Problemas.crear(HttpStatus.UNAUTHORIZED, "NO_AUTENTICADO",
                    "Inicie sesion en el banco", request.getRequestURI()));
        };
    }

    /** Fuerza a emitir la cookie XSRF-TOKEN en cada respuesta para que la pagina la tenga antes de su primer POST. */
    static final class CookieCsrf extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (request.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken token) {
                token.getToken();
            }
            chain.doFilter(request, response);
        }
    }
}
