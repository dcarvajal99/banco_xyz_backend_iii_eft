package com.bancoxyz.bff.cajero.seguridad;

import java.io.IOException;
import java.util.Objects;

import com.bancoxyz.bff.cajero.error.Acciones;
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
 * Despues de validar el token: la sesion solo sirve en el cajero donde se abrio y mientras no se haya cerrado.
 * Un token copiado de la pantalla de un cajero no sirve en otro.
 */
public class FiltroSesionDeTerminal extends OncePerRequestFilter {

    private final RegistroDeRevocaciones revocaciones;
    private final RespuestasDeSeguridad respuestas;

    public FiltroSesionDeTerminal(RegistroDeRevocaciones revocaciones, RespuestasDeSeguridad respuestas) {
        this.revocaciones = revocaciones;
        this.respuestas = respuestas;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/cajero/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication autenticacion = SecurityContextHolder.getContext().getAuthentication();
        if (autenticacion instanceof JwtAuthenticationToken token) {
            Jwt jwt = token.getToken();
            if (revocaciones.estaRevocado(jwt.getId())) {
                respuestas.escribir(response, 401, "SESION_FINALIZADA", "Su sesión terminó. Retire su tarjeta.",
                        Acciones.DEVOLVER_TARJETA);
                return;
            }
            if (!Objects.equals(jwt.getClaimAsString("terminal"), request.getHeader(FiltroFirmaDeTerminal.ENCABEZADO_TERMINAL))) {
                respuestas.escribir(response, 401, "SESION_DE_OTRO_TERMINAL", "Su sesión no es válida en este cajero.",
                        Acciones.DEVOLVER_TARJETA);
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
