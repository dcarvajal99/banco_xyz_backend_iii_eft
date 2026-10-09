package com.bancoxyz.bff.cajero.seguridad;

import java.io.IOException;

import com.bancoxyz.bff.cajero.error.Acciones;
import com.bancoxyz.bff.cajero.error.Problemas;
import com.bancoxyz.bff.cajero.error.RespuestaDeError;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Rechazos de seguridad en el formato del cajero. Se usa en los filtros propios y como entry point del
 * resource server: sin esto, un token invalido responderia 401 con cuerpo vacio y el terminal no sabria
 * que hacer con la tarjeta.
 */
public class RespuestasDeSeguridad implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final String TERMINAL_NO_AUTORIZADO = "Operación no disponible en este cajero.";

    private final ObjectMapper json;

    public RespuestasDeSeguridad(ObjectMapper json) {
        this.json = json;
    }

    /** Token de sesion ausente, vencido, de otro canal o con firma invalida. */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        escribir(response, 401, "SESION_FINALIZADA", "Su sesión terminó. Retire su tarjeta.", Acciones.DEVOLVER_TARJETA);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        escribir(response, 403, "ACCESO_DENEGADO", TERMINAL_NO_AUTORIZADO, Acciones.DEVOLVER_TARJETA);
    }

    public void rechazarTerminal(HttpServletResponse response, String codigo) throws IOException {
        escribir(response, 401, codigo, TERMINAL_NO_AUTORIZADO, Acciones.DEVOLVER_TARJETA);
    }

    public void escribir(HttpServletResponse response, int estado, String codigo, String mensaje, String accion)
            throws IOException {
        response.setStatus(estado);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        String ruta = RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes atributos
                ? atributos.getRequest().getRequestURI() : "/";
        json.writeValue(response.getOutputStream(), Problemas.desde(HttpStatusCode.valueOf(estado),
                new RespuestaDeError(codigo, mensaje, accion, null), ruta));
    }
}
