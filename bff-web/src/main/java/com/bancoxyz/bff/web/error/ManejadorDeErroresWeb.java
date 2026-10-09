package com.bancoxyz.bff.web.error;

import java.net.URI;
import java.util.Map;

import com.bancoxyz.bff.web.config.FiltroCorrelacion;
import com.bancoxyz.bff.web.core.ErrorDelCore;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Traduccion de errores del canal web (seccion 12.2): un codigo conocido del core pasa con su status; uno
 * desconocido, o un 401/403 del core (el BFF esta mal configurado, no es culpa del usuario), sale como 502.
 */
@RestControllerAdvice
public class ManejadorDeErroresWeb extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ManejadorDeErroresWeb.class);

    private static final Map<String, String> CONOCIDOS = Map.of(
            "CREDENCIALES_INVALIDAS", "Credenciales inválidas",
            "USUARIO_BLOQUEADO", "Usuario bloqueado",
            "CUENTA_NO_ENCONTRADA", "Cuenta no encontrada",
            "CLIENTE_NO_ENCONTRADO", "Cliente no encontrado",
            "SIN_CIERRE_PUBLICADO", "Sin cierre publicado",
            "SOLICITUD_INVALIDA", "Solicitud inválida");

    @ExceptionHandler(ErrorWeb.class)
    public ResponseEntity<Object> web(ErrorWeb error, HttpServletRequest request) {
        return responder(ProblemasWeb.crear(error.getEstado(), error.getCodigo(), error.getTitulo(), error.getMessage(),
                request.getRequestURI()));
    }

    @ExceptionHandler(ErrorDelCore.class)
    public ResponseEntity<Object> core(ErrorDelCore error, HttpServletRequest request) {
        String titulo = error.getCodigo() == null ? null : CONOCIDOS.get(error.getCodigo());
        if (titulo == null) {
            log.warn("Respuesta del core no traducible: {} {}", error.getEstado(), error.getCodigo());
            return responder(ProblemasWeb.crear(HttpStatus.BAD_GATEWAY, "ERROR_CORE", "Error del backend principal",
                    "El backend principal respondio un error inesperado", request.getRequestURI()));
        }
        return responder(ProblemasWeb.crear(HttpStatusCode.valueOf(error.getEstado()), error.getCodigo(), titulo, titulo,
                request.getRequestURI()));
    }

    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<Object> coreInalcanzable(RestClientException error, HttpServletRequest request) {
        log.warn("Core inalcanzable: {}", error.getMessage());
        return responder(ProblemasWeb.crear(HttpStatus.SERVICE_UNAVAILABLE, "SERVICIO_NO_DISPONIBLE", "Servicio no disponible",
                "El backend principal no responde", request.getRequestURI()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Object> parametro(MethodArgumentTypeMismatchException error, HttpServletRequest request) {
        return responder(ProblemasWeb.crear(HttpStatus.BAD_REQUEST, "SOLICITUD_INVALIDA", "Solicitud inválida",
                "Parámetro inválido: " + error.getName(), request.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> inesperado(Exception error, HttpServletRequest request) {
        log.error("Error no controlado en el BFF web", error);
        return responder(ProblemasWeb.crear(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR_INTERNO", "Error interno",
                "Error interno del servicio", request.getRequestURI()));
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(@Nullable Object body, HttpHeaders headers,
                                                          HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problema) {
            String codigo = statusCode.value() == 404 ? "RECURSO_NO_ENCONTRADO"
                    : statusCode.is5xxServerError() ? "ERROR_INTERNO" : "SOLICITUD_INVALIDA";
            problema.setProperty("codigo", codigo);
            problema.setProperty("correlacionId", FiltroCorrelacion.actual());
            if (problema.getInstance() == null && request instanceof ServletWebRequest servlet) {
                problema.setInstance(URI.create(servlet.getRequest().getRequestURI()));
            }
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private static ResponseEntity<Object> responder(ProblemDetail problema) {
        return ResponseEntity.status(problema.getStatus()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problema);
    }
}
