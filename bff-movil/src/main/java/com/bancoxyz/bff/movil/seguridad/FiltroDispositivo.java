package com.bancoxyz.bff.movil.seguridad;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Despues de validar el token: el dispositivo declarado debe ser el del token y la familia no debe estar revocada.
 *
 * <p>Honestidad sobre lo que esto protege: el id de dispositivo lo elige la app y viaja legible dentro del JWT.
 * Ata la sesion al dispositivo que se declaro y permite revocar por dispositivo, pero NO es prueba de posesion:
 * quien copie el token junto con su id puede usarlo en otro equipo. La solucion real (DPoP, RFC 9449, con una
 * clave no exportable del telefono) queda fuera del alcance de esta entrega.</p>
 */
public class FiltroDispositivo extends OncePerRequestFilter {

    public static final String ENCABEZADO = "X-Dispositivo-Id";

    private final AlmacenDeRefresh refresh;
    private final ErroresDeSeguridadMovil errores;

    public FiltroDispositivo(AlmacenDeRefresh refresh, ErroresDeSeguridadMovil errores) {
        this.refresh = refresh;
        this.errores = errores;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication autenticacion = SecurityContextHolder.getContext().getAuthentication();
        if (autenticacion instanceof JwtAuthenticationToken token) {
            Jwt jwt = token.getToken();
            String esperado = jwt.getClaimAsString("dispositivo");
            if (esperado == null || !esperado.equals(request.getHeader(ENCABEZADO))) {
                errores.escribir(response, 401, "DISPOSITIVO_NO_COINCIDE", "Sesión de otro dispositivo.");
                return;
            }
            if (refresh.familiaRevocada(jwt.getClaimAsString("fam"))) {
                errores.escribir(response, 401, "SESION_REVOCADA", "Su sesión fue cerrada. Inicie sesión nuevamente.");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
