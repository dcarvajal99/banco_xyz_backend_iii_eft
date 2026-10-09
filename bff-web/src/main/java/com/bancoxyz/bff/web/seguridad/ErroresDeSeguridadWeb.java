package com.bancoxyz.bff.web.seguridad;

import java.io.IOException;

import com.bancoxyz.bff.web.error.ProblemasWeb;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;

/** 401 y 403 como ProblemDetail JSON: una SPA necesita un codigo, no una redireccion a una pagina de login. */
public class ErroresDeSeguridadWeb implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper json;

    public ErroresDeSeguridadWeb(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        escribir(response, ProblemasWeb.crear(HttpStatus.UNAUTHORIZED, "NO_AUTENTICADO", "No autenticado",
                "Inicie sesión para continuar", request.getRequestURI()));
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        if (ex instanceof CsrfException) {
            escribir(response, ProblemasWeb.crear(HttpStatus.FORBIDDEN, "CSRF_INVALIDO", "Token CSRF inválido",
                    "Falta o no coincide el token CSRF", request.getRequestURI()));
            return;
        }
        escribir(response, ProblemasWeb.crear(HttpStatus.FORBIDDEN, "ACCESO_DENEGADO", "Acceso denegado",
                "Su rol no permite esta operación", request.getRequestURI()));
    }

    private void escribir(HttpServletResponse response, ProblemDetail problema) throws IOException {
        response.setStatus(problema.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        json.writeValue(response.getOutputStream(), problema);
    }
}
