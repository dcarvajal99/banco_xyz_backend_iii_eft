package com.bancoxyz.clientes.error;

import java.net.URI;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.bancoxyz.clientes.config.FiltroCorrelacion;
import org.slf4j.MDC;

/**
 * Traduce toda excepcion a un ProblemDetail con {@code codigo} y {@code correlacionId}.
 *
 * <p>Extiende {@link ResponseEntityExceptionHandler} para cubrir los errores propios de Spring MVC
 * (cuerpo ilegible, validacion, metodo no soportado) y sobrescribe {@code createResponseEntity}
 * para que tambien esos lleven el codigo: un BFF que recibe un error sin codigo no sabe traducirlo.</p>
 */
@RestControllerAdvice
public class ManejadorDeErrores extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ManejadorDeErrores.class);

    @ExceptionHandler(ErrorDeNegocio.class)
    public ResponseEntity<Object> negocio(ErrorDeNegocio ex, HttpServletRequest request) {
        ProblemDetail problema = Problemas.crear(ex.getEstado(), ex.getCodigo(), ex.getTitulo(), ex.getMessage(),
                request.getRequestURI());
        ex.getExtras().forEach(problema::setProperty);
        return responder(problema);
    }

    /**
     * Unicidad (nombre de cliente o de usuario) que dos altas simultaneas alcanzaron a violar en la base: la validacion
     * previa del servicio no ve la fila que la otra transaccion aun no confirma.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Object> integridad(DataIntegrityViolationException ex, HttpServletRequest request) {
        log.warn("Violacion de integridad traducida a CLIENTE_YA_EXISTE: {}", ex.getMostSpecificCause().getMessage());
        return negocio(Errores.clienteYaExiste(), request);
    }

    /** Version optimista del perfil: otra edicion se confirmo primero. */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Object> versionDistinta(ObjectOptimisticLockingFailureException ex, HttpServletRequest request) {
        return negocio(Errores.perfilModificado(), request);
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, ConstraintViolationException.class})
    public ResponseEntity<Object> parametroInvalido(RuntimeException ex, HttpServletRequest request) {
        return negocio(Errores.solicitudInvalida("Parametro invalido"), request);
    }

    /** Validacion del DTO de entrada: ademas del codigo, que campo fallo y por que, para que el BFF lo muestre. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        ProblemDetail problema = Problemas.crear(HttpStatus.BAD_REQUEST, "SOLICITUD_INVALIDA", "Solicitud invalida",
                "Uno o mas campos no son validos", ((ServletWebRequest) request).getRequest().getRequestURI());
        problema.setProperty("errores", ex.getBindingResult().getFieldErrors().stream()
                .map(e -> Map.of("campo", e.getField(), "mensaje", String.valueOf(e.getDefaultMessage())))
                .toList());
        return responder(problema);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> inesperado(Exception ex, HttpServletRequest request) {
        log.error("Error no controlado en {}", request.getRequestURI(), ex);
        ProblemDetail problema = Problemas.crear(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR_INTERNO", "Error interno",
                "Error interno del servicio", request.getRequestURI());
        return responder(problema);
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(@Nullable Object body, HttpHeaders headers,
                                                          HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problema) {
            String codigo = codigoPorEstado(statusCode);
            problema.setType(URI.create("urn:bancoxyz:problema:" + codigo.toLowerCase().replace('_', '-')));
            problema.setProperty("codigo", codigo);
            problema.setProperty("correlacionId", MDC.get(FiltroCorrelacion.CLAVE_MDC));
            if (problema.getInstance() == null && request instanceof ServletWebRequest servlet) {
                problema.setInstance(URI.create(servlet.getRequest().getRequestURI()));
            }
            if (problema.getDetail() == null) {
                problema.setDetail(problema.getTitle());
            }
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    static String codigoPorEstado(HttpStatusCode estado) {
        if (estado.value() == 404) {
            return "RECURSO_NO_ENCONTRADO";
        }
        if (estado.value() == 405) {
            return "METODO_NO_PERMITIDO";
        }
        return estado.is5xxServerError() ? "ERROR_INTERNO" : "SOLICITUD_INVALIDA";
    }

    private static ResponseEntity<Object> responder(ProblemDetail problema) {
        return ResponseEntity.status(problema.getStatus()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problema);
    }
}
