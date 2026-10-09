package com.bancoxyz.pagos.seguridad;

import java.io.IOException;

import com.bancoxyz.pagos.error.Problemas;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * 401 y 403 de Spring Security, que ocurren fuera de MVC, con el mismo ProblemDetail que el resto de los errores.
 *
 * <p>Sin esto, un token vencido o un scope que falta responderian con el cuerpo vacio y solo el encabezado
 * {@code WWW-Authenticate}: el BFF no tendria un {@code codigo} que traducir.</p>
 */
@Component
public class ProblemasDeSeguridad implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public ProblemasDeSeguridad(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        if (ex instanceof InvalidBearerTokenException) {
            rechazar(request, response, HttpStatus.UNAUTHORIZED, "TOKEN_INVALIDO", "Token invalido",
                    "El access token esta vencido, mal firmado o es para otra audiencia");
            return;
        }
        rechazar(request, response, HttpStatus.UNAUTHORIZED, "NO_AUTENTICADO", "No autenticado",
                "Falta la credencial o no es valida");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        rechazar(request, response, HttpStatus.FORBIDDEN, "ACCESO_DENEGADO", "Acceso denegado",
                "La credencial no tiene permiso (scope o rol) sobre este recurso");
    }

    public void rechazar(HttpServletRequest request, HttpServletResponse response, HttpStatus estado, String codigo,
                         String titulo, String detalle) throws IOException {
        ProblemDetail problema = Problemas.crear(estado, codigo, titulo, detalle, request.getRequestURI());
        response.setStatus(problema.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problema);
    }
}
