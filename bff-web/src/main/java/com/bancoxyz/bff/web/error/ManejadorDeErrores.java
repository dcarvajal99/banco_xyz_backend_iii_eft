package com.bancoxyz.bff.web.error;

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
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Todo error del BFF web sale como el ProblemDetail comun; el de un microservicio pasa tal cual (mismo codigo). */
@RestControllerAdvice
public class ManejadorDeErrores extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ManejadorDeErrores.class);

    @ExceptionHandler(ErrorDeServicio.class)
    public ResponseEntity<Object> deServicio(ErrorDeServicio error) {
        return ResponseEntity.status(error.getEstado()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(error.getProblema());
    }

    @ExceptionHandler(ServicioNoDisponible.class)
    public ResponseEntity<Object> noDisponible(ServicioNoDisponible error, HttpServletRequest request) {
        log.warn("{}: {}", error.getMessage(), error.getCause() == null ? "" : error.getCause().toString());
        return responder(Problemas.crear(HttpStatus.SERVICE_UNAVAILABLE, "SERVICIO_NO_DISPONIBLE",
                "El servicio " + error.getServicio() + " no esta disponible. Intente en unos segundos.", request.getRequestURI()));
    }

    @ExceptionHandler(AccesoDeEjecutivo.class)
    public ResponseEntity<Object> soloEjecutivo(AccesoDeEjecutivo error, HttpServletRequest request) {
        return responder(Problemas.crear(HttpStatus.FORBIDDEN, "ACCESO_DENEGADO", "Solo para ejecutivos del banco",
                request.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> inesperado(Exception error, HttpServletRequest request) {
        log.error("Error no controlado en {}", request.getRequestURI(), error);
        return responder(Problemas.crear(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR_INTERNO", "Error interno del BFF web",
                request.getRequestURI()));
    }

    /** Errores propios de Spring MVC (JSON ilegible, metodo no soportado, 404) con el mismo formato. */
    @Override
    protected ResponseEntity<Object> createResponseEntity(@Nullable Object body, HttpHeaders headers,
                                                          HttpStatusCode statusCode, WebRequest request) {
        String codigo = statusCode.value() == 404 ? "RECURSO_NO_ENCONTRADO"
                : statusCode.is5xxServerError() ? "ERROR_INTERNO" : "SOLICITUD_INVALIDA";
        String detalle = body instanceof ProblemDetail p && p.getDetail() != null ? p.getDetail() : codigo;
        return responder(Problemas.crear(statusCode, codigo, detalle,
                ((ServletWebRequest) request).getRequest().getRequestURI()));
    }

    private static ResponseEntity<Object> responder(ProblemDetail problema) {
        return ResponseEntity.status(problema.getStatus()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problema);
    }

    /** La sesion es de un cliente y la ruta es de la consola del ejecutivo. */
    public static class AccesoDeEjecutivo extends RuntimeException {
    }
}
