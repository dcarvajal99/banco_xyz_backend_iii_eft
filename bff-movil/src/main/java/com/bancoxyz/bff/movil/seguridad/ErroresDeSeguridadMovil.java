package com.bancoxyz.bff.movil.seguridad;

import java.io.IOException;

import com.bancoxyz.bff.movil.error.Problemas;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

public class ErroresDeSeguridadMovil implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper json;

    public ErroresDeSeguridadMovil(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        if (ex instanceof InvalidBearerTokenException) {
            escribir(response, 401, "TOKEN_INVALIDO", "Sesión vencida o inválida.");
        } else {
            escribir(response, 401, "NO_AUTENTICADO", "Inicie sesión.");
        }
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        escribir(response, 403, "ACCESO_DENEGADO", "No tiene permiso.");
    }

    public void escribir(HttpServletResponse response, int estado, String codigo, String mensaje) throws IOException {
        response.setStatus(estado);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        json.writeValue(response.getOutputStream(), Problemas.crear(HttpStatusCode.valueOf(estado), codigo, mensaje,
                RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a ? a.getRequest().getRequestURI() : "/"));
    }
}
