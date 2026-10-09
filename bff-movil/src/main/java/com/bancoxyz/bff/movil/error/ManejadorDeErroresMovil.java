package com.bancoxyz.bff.movil.error;

import java.util.Map;

import com.bancoxyz.bff.movil.core.ErrorDelCore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
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
            "CREDENCIALES_INVALIDAS", "Usuario o clave incorrectos.",
            "USUARIO_BLOQUEADO", "Demasiados intentos. Intente en 15 minutos.",
            "ROL_NO_PERMITIDO_EN_CANAL", "Este usuario no puede ingresar desde la app.",
            "CUENTA_NO_ENCONTRADA", "Cuenta no encontrada.",
            "CLIENTE_NO_ENCONTRADO", "Cliente no encontrado.",
            "SIN_CIERRE_PUBLICADO", "Información no disponible por el momento.",
            "SOLICITUD_INVALIDA", "Solicitud inválida.");

    @ExceptionHandler(ErrorMovil.class)
    public ResponseEntity<RespuestaErrorMovil> movil(ErrorMovil error) {
        return ResponseEntity.status(error.getEstado()).body(new RespuestaErrorMovil(error.getCodigo(), error.getMessage()));
    }

    @ExceptionHandler(ErrorDelCore.class)
    public ResponseEntity<RespuestaErrorMovil> core(ErrorDelCore error) {
        String mensaje = error.getCodigo() == null ? null : CONOCIDOS.get(error.getCodigo());
        if (mensaje == null) {
            log.warn("Respuesta del core no traducible: {} {}", error.getEstado(), error.getCodigo());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(new RespuestaErrorMovil("ERROR_CORE", "No pudimos completar la solicitud."));
        }
        return ResponseEntity.status(error.getEstado()).body(new RespuestaErrorMovil(error.getCodigo(), mensaje));
    }

    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<RespuestaErrorMovil> coreInalcanzable(RestClientException error) {
        log.warn("Core inalcanzable: {}", error.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new RespuestaErrorMovil("SERVICIO_NO_DISPONIBLE", "Servicio no disponible. Intente nuevamente."));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, HttpMediaTypeNotSupportedException.class})
    public ResponseEntity<RespuestaErrorMovil> solicitudInvalida(Exception error) {
        return ResponseEntity.badRequest().body(new RespuestaErrorMovil("SOLICITUD_INVALIDA", "Solicitud inválida."));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<RespuestaErrorMovil> noEncontrado(NoResourceFoundException error) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new RespuestaErrorMovil("RECURSO_NO_ENCONTRADO", "No encontrado."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<RespuestaErrorMovil> inesperado(Exception error) {
        log.error("Error no controlado en el BFF movil", error);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new RespuestaErrorMovil("ERROR_INTERNO", "Ocurrió un error. Intente nuevamente."));
    }
}
