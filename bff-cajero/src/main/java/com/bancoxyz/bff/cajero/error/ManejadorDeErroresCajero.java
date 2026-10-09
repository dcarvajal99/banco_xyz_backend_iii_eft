package com.bancoxyz.bff.cajero.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
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
    public ResponseEntity<RespuestaDeError> cajero(ErrorDeCajero error) {
        return ResponseEntity.status(error.getEstado()).body(error.getRespuesta());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingRequestHeaderException.class,
            HttpMediaTypeNotSupportedException.class, HttpRequestMethodNotSupportedException.class})
    public ResponseEntity<RespuestaDeError> solicitudInvalida(Exception error) {
        return ResponseEntity.badRequest().body(new RespuestaDeError("SOLICITUD_INVALIDA",
                "Operación no válida.", Acciones.MOSTRAR_MENU, null));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<RespuestaDeError> noEncontrado(NoResourceFoundException error) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new RespuestaDeError("RECURSO_NO_ENCONTRADO",
                "Operación no disponible.", Acciones.MOSTRAR_MENU, null));
    }

    /** Ante lo inesperado el cajero devuelve la tarjeta: nunca queda en un estado que dispense a ciegas. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<RespuestaDeError> inesperado(Exception error) {
        log.error("Error no controlado en el BFF cajero", error);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new RespuestaDeError("SERVICIO_NO_DISPONIBLE",
                "Servicio no disponible. Retire su tarjeta.", Acciones.DEVOLVER_TARJETA, null));
    }
}
