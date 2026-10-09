package com.bancoxyz.bff.movil.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class ConfiguracionGeneralTest {

    @Test
    @DisplayName("Saturado no rechaza: sin hilo libre la tarea corre en el hilo que la pidio (antes: TaskRejectedException y 500)")
    void saturadoNoRechaza() {
        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) new ConfiguracionGeneral().portadaMovil(propiedades(1));
        try {
            List<CompletableFuture<String>> tareas = IntStream.range(0, 20)
                    .mapToObj(i -> CompletableFuture.supplyAsync(ConfiguracionGeneralTest::dormirYNombrarHilo, pool)).toList();
            List<String> hilos = tareas.stream().map(CompletableFuture::join).toList();
            assertThat(hilos).hasSize(20)
                    .anyMatch(hilo -> hilo.startsWith("portada-movil-"))
                    .anyMatch(hilo -> !hilo.startsWith("portada-movil-"));
        } finally {
            pool.shutdown();
        }
    }

    private static String dormirYNombrarHilo() {
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Thread.currentThread().getName();
    }

    @Test
    @DisplayName("Portada: con hilos un pool acotado; con 0, en serie en el hilo de la solicitud (solo para medir)")
    void ejecutorDeLaPortada() {
        ConfiguracionGeneral configuracion = new ConfiguracionGeneral();
        assertThat(configuracion.portadaMovil(propiedades(4)))
                .isInstanceOfSatisfying(ThreadPoolTaskExecutor.class, pool -> {
                    assertThat(pool.getMaxPoolSize()).isEqualTo(4);
                    pool.shutdown();
                });
        assertThat(configuracion.portadaMovil(propiedades(0))).isInstanceOf(SyncTaskExecutor.class);
    }

    private static PropiedadesMovil propiedades(int hilosPortada) {
        return new PropiedadesMovil("banca-movil", "https://localhost:8102/api/v1", 10, hilosPortada);
    }
}
