package com.bancoxyz.bff.cajero;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;

/**
 * Cierra todos los circuitos antes de cada prueba. Las pruebas corren con la politica de produccion (abre con 3 fallas
 * en 6 llamadas) y comparten el contexto de Spring: sin esto, las fallas simuladas de una prueba abririan el circuito de
 * la siguiente. Registrado en META-INF/spring.factories.
 */
public class ReinicioDeCircuitos implements TestExecutionListener {

    @Override
    public void beforeTestMethod(TestContext contexto) {
        if (contexto.hasApplicationContext()) {
            contexto.getApplicationContext().getBeanProvider(CircuitBreakerRegistry.class)
                    .ifAvailable(registro -> registro.getAllCircuitBreakers().forEach(CircuitBreaker::reset));
        }
    }
}
