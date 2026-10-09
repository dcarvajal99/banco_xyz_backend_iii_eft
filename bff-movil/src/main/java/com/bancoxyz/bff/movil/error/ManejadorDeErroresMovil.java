package com.bancoxyz.bff.movil.error;

import java.util.Map;

import com.bancoxyz.bff.movil.core.ErrorDelCore;
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
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Traduce errores al formato de la app. Un codigo conocido del core pasa con su mismo status; uno desconocido
 * (o un 401/403 del core, que indica un BFF mal configurado) se entrega como 502 ERROR_CORE.
 */
@RestControllerAdvice
public class ManejadorDeErroresMovil {

    private static final Logger log = LoggerFactory.getLogger(ManejadorDeErroresMovil.class);

    private static final Map<String, String> CONOCIDOS = Map.of(
                        "SALDO_INSUFICIENTE", "Saldo insuficiente.",
            "MISMA_CUENTA", "Elija dos cuentas distintas.",
            "CUENTA_ORIGEN_NO_ENCONTRADA", "Cuenta de origen no encontrada.",
            "IDEMPOTENCIA_CONFLICTO", "Esa operacion ya se envio con otros datos.",
            "PAGO_NO_ENCONTRADO", "Operacion no encontrada.",
            "CUENTA_NO_ENCONTRADA", "Cuenta no encontrada.",
            "CLIENTE_NO_ENCONTRADO", "Cliente no encontrado.",
            "SIN_CIERRE_PUBLICADO", "Información no disponible por el momento.",
            "SOLICITUD_INVALIDA", "Solicitud inválida.");

    @ExceptionHandler(ErrorMovil.class)
    public ResponseEntity<ProblemDetail> movil(ErrorMovil error, HttpServletRequest request) {
        return responder(error.getEstado(), error.getCodigo(), error.getMessage(), request);
    }

    @ExceptionHandler(ErrorDelCore.class)
    public ResponseEntity<ProblemDetail> core(ErrorDelCore error, HttpServletRequest request) {
        String mensaje = error.getCodigo() == null ? null : CONOCIDOS.get(error.getCodigo());
        if (mensaje == null) {
            log.warn("Respuesta del core no traducible: {} {}", error.getEstado(), error.getCodigo());
            return responder(HttpStatus.BAD_GATEWAY, "ERROR_CORE", "No pudimos completar la solicitud.", request);
        }
        return responder(HttpStatusCode.valueOf(error.getEstado()), error.getCodigo(), mensaje, request);
    }

    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<ProblemDetail> coreInalcanzable(RestClientException error, HttpServletRequest request) {
        log.warn("Core inalcanzable: {}", error.getMessage());
        return responder(HttpStatus.SERVICE_UNAVAILABLE, "SERVICIO_NO_DISPONIBLE", "Servicio no disponible. Intente nuevamente.", request);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, HttpMediaTypeNotSupportedException.class})
    public ResponseEntity<ProblemDetail> solicitudInvalida(Exception error, HttpServletRequest request) {
        return responder(HttpStatus.BAD_REQUEST, "SOLICITUD_INVALIDA", "Solicitud inválida.", request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ProblemDetail> noEncontrado(NoResourceFoundException error, HttpServletRequest request) {
        return responder(HttpStatus.NOT_FOUND, "RECURSO_NO_ENCONTRADO", "No encontrado.", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> inesperado(Exception error, HttpServletRequest request) {
        log.error("Error no controlado en el BFF movil", error);
        return responder(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR_INTERNO", "Ocurrió un error. Intente nuevamente.", request);
    }

    private static ResponseEntity<ProblemDetail> responder(HttpStatusCode estado, String codigo, String mensaje,
                                                           HttpServletRequest request) {
        return ResponseEntity.status(estado).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(Problemas.crear(estado, codigo, mensaje, request.getRequestURI()));
    }
}
