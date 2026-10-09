package com.bancoxyz.bff.web.panel;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.bancoxyz.bff.web.error.ErrorWeb;
import org.springframework.http.HttpStatus;

/**
 * Espera de las tareas del agregador con un plazo total comun.
 *
 * <p>Dos modos: un bloque ESENCIAL propaga su error (sin cuentas no hay panel); uno NO esencial se reemplaza por su
 * valor vacio y deja un texto en {@code advertencias}, y el panel se entrega igual: degradacion elegante.</p>
 */
final class Espera {

    private final long limiteNanos;

    Espera(long segundos) {
        this.limiteNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(segundos);
    }

    <T> T esencial(CompletableFuture<T> tarea) {
        try {
            return tarea.get(restante(), TimeUnit.NANOSECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException causa) {
                throw causa;
            }
            throw new IllegalStateException(e.getCause());
        } catch (TimeoutException e) {
            throw new ErrorWeb(HttpStatus.SERVICE_UNAVAILABLE, "SERVICIO_NO_DISPONIBLE", "Servicio no disponible",
                    "El backend principal no respondio a tiempo");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    <T> T opcional(CompletableFuture<T> tarea, T siFalla, List<String> advertencias, String aviso) {
        try {
            return tarea.get(restante(), TimeUnit.NANOSECONDS);
        } catch (ExecutionException | TimeoutException e) {
            advertencias.add(aviso);
            return siFalla;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            advertencias.add(aviso);
            return siFalla;
        }
    }

    private long restante() {
        return Math.max(0, limiteNanos - System.nanoTime());
    }
}
