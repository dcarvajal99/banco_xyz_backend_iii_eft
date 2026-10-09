package com.bancoxyz.bff.cajero.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ManejadorDeErroresCajero {

    private static final Logger log = LoggerFactory.getLogger(ManejadorDeErroresCajero.class);

    @ExceptionHandler(ErrorDeCajero.class)
    public ResponseEntity<ProblemDetail> cajero(ErrorDeCajero error, HttpServletRequest request) {
        return responder(error.getEstado(), error.getRespuesta(), request);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingRequestHeaderException.class,
            HttpMediaTypeNotSupportedException.class, HttpRequestMethodNotSupportedException.class})
    public ResponseEntity<ProblemDetail> solicitudInvalida(Exception error, HttpServletRequest request) {
        return responder(HttpStatus.BAD_REQUEST, new RespuestaDeError("SOLICITUD_INVALIDA",
                "Operación no válida.", Acciones.MOSTRAR_MENU, null), request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ProblemDetail> noEncontrado(NoResourceFoundException error, HttpServletRequest request) {
        return responder(HttpStatus.NOT_FOUND, new RespuestaDeError("RECURSO_NO_ENCONTRADO",
                "Operación no disponible.", Acciones.MOSTRAR_MENU, null), request);
    }

    /** Ante lo inesperado el cajero devuelve la tarjeta: nunca queda en un estado que dispense a ciegas. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> inesperado(Exception error, HttpServletRequest request) {
        log.error("Error no controlado en el BFF cajero", error);
        return responder(HttpStatus.SERVICE_UNAVAILABLE, new RespuestaDeError("SERVICIO_NO_DISPONIBLE",
                "Servicio no disponible. Retire su tarjeta.", Acciones.DEVOLVER_TARJETA, null), request);
    }

    private static ResponseEntity<ProblemDetail> responder(HttpStatusCode estado, RespuestaDeError error,
                                                           HttpServletRequest request) {
        return ResponseEntity.status(estado).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(Problemas.desde(estado, error, request.getRequestURI()));
    }
}
