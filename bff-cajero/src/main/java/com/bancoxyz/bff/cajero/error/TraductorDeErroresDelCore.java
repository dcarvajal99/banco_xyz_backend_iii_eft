package com.bancoxyz.bff.cajero.error;

import java.net.ConnectException;

import com.bancoxyz.bff.cajero.core.CircuitoDelCoreAbierto;
import com.bancoxyz.bff.cajero.core.CoreNoRegistrado;
import com.bancoxyz.bff.cajero.core.ErrorDelCore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;

/**
 * Traduce lo que respondio (o no respondio) el core al idioma del cajero.
 *
 * <h2>El caso que importa: el retiro sin respuesta</h2>
 * <p>Si el core no contesta a tiempo, el BFF no sabe si el dinero se desconto. No puede decir "rechazado"
 * (quiza se desconto y el cliente reclamaria) ni "dispense" (quiza no). Responde 504
 * {@code RESULTADO_INCIERTO} con la accion {@code REINTENTAR_MISMA_CLAVE}: el core es idempotente, asi que
 * reintentar con la misma clave devuelve el resultado original sin descontar dos veces.</p>
 *
 * <p>Distinto es no haber podido conectar, tener el circuito del core abierto o no encontrar el core en Eureka: la
 * solicitud nunca salio, no pudo haber descuento, y se responde 503 devolviendo la tarjeta.</p>
 */
@Component
public class TraductorDeErroresDelCore {

    public ErrorDeCajero enSesion(RuntimeException error) {
        if (error instanceof ErrorDelCore core && core.getCodigo() != null) {
            switch (core.getCodigo()) {
                case "PIN_INCORRECTO" -> {
                    int restantes = core.getIntentosRestantes() == null ? 0 : core.getIntentosRestantes();
                    String texto = restantes == 1 ? "PIN incorrecto. Le queda 1 intento."
                            : "PIN incorrecto. Le quedan " + restantes + " intentos.";
                    return new ErrorDeCajero(HttpStatus.UNAUTHORIZED, "PIN_INCORRECTO", texto, Acciones.SOLICITAR_PIN,
                            restantes);
                }
                case "TARJETA_BLOQUEADA" -> {
                    return tarjetaBloqueada();
                }
                case "TARJETA_INVALIDA" -> {
                    return new ErrorDeCajero(HttpStatus.UNAUTHORIZED, "TARJETA_INVALIDA", "Tarjeta no válida.",
                            Acciones.DEVOLVER_TARJETA);
                }
                default -> {
                    return servicioNoDisponible();
                }
            }
        }
        return servicioNoDisponible();
    }

    public ErrorDeCajero enConsulta(RuntimeException error) {
        return servicioNoDisponible();
    }

    public ErrorDeCajero enRetiro(RuntimeException error) {
        if (error instanceof ErrorDelCore core) {
            String codigo = core.getCodigo() == null ? "" : core.getCodigo();
            switch (codigo) {
                case "SALDO_INSUFICIENTE" -> {
                    return new ErrorDeCajero(HttpStatus.UNPROCESSABLE_ENTITY, codigo,
                            "Saldo insuficiente para este monto.", Acciones.MOSTRAR_MENU);
                }
                case "LIMITE_DIARIO_EXCEDIDO" -> {
                    return new ErrorDeCajero(HttpStatus.UNPROCESSABLE_ENTITY, codigo,
                            "El monto supera su límite diario de retiro.", Acciones.MOSTRAR_MENU);
                }
                case "CUENTA_NO_ADMITE_RETIROS" -> {
                    return new ErrorDeCajero(HttpStatus.UNPROCESSABLE_ENTITY, codigo,
                            "Esta cuenta no permite retiros.", Acciones.MOSTRAR_MENU);
                }
                case "MONTO_INVALIDO" -> {
                    return new ErrorDeCajero(HttpStatus.BAD_REQUEST, codigo, "Monto no válido.", Acciones.MOSTRAR_MENU);
                }
                case "IDEMPOTENCIA_CONFLICTO" -> {
                    return new ErrorDeCajero(HttpStatus.CONFLICT, codigo, "La operación no pudo completarse.",
                            Acciones.NINGUNA);
                }
                case "TARJETA_BLOQUEADA" -> {
                    return tarjetaBloqueada();
                }
                case "CUENTA_OCUPADA" -> {
                    return new ErrorDeCajero(HttpStatus.SERVICE_UNAVAILABLE, codigo,
                            "Estamos procesando otra operación. Intente nuevamente.", Acciones.REINTENTAR_MISMA_CLAVE);
                }
                default -> {
                    // Un 5xx desconocido pudo ocurrir DESPUES de descontar: incierto. Un 4xx no listado (por
                    // ejemplo, una credencial de servicio mal configurada) ocurre antes: no hubo descuento.
                    return core.getEstado() >= 500 ? resultadoIncierto() : servicioNoDisponible();
                }
            }
        }
        if (error instanceof CircuitoDelCoreAbierto || error instanceof CoreNoRegistrado
                || error instanceof ResourceAccessException acceso && causadoPor(acceso, ConnectException.class)) {
            return servicioNoDisponible();
        }
        // Timeout, corte a mitad de la respuesta o cuerpo ilegible: la solicitud llego y no sabemos que paso.
        return resultadoIncierto();
    }

    private static ErrorDeCajero tarjetaBloqueada() {
        return new ErrorDeCajero(HttpStatus.LOCKED, "TARJETA_BLOQUEADA", "Tarjeta bloqueada. Contacte a su banco.",
                Acciones.RETENER_TARJETA);
    }

    private static ErrorDeCajero servicioNoDisponible() {
        return new ErrorDeCajero(HttpStatus.SERVICE_UNAVAILABLE, "SERVICIO_NO_DISPONIBLE",
                "Servicio no disponible. Retire su tarjeta.", Acciones.DEVOLVER_TARJETA);
    }

    private static ErrorDeCajero resultadoIncierto() {
        return new ErrorDeCajero(HttpStatus.GATEWAY_TIMEOUT, "RESULTADO_INCIERTO", "Estamos verificando su operación.",
                Acciones.REINTENTAR_MISMA_CLAVE);
    }

    private static boolean causadoPor(Throwable error, Class<? extends Throwable> tipo) {
        for (Throwable causa = error; causa != null; causa = causa.getCause()) {
            if (tipo.isInstance(causa)) {
                return true;
            }
        }
        return false;
    }
}
