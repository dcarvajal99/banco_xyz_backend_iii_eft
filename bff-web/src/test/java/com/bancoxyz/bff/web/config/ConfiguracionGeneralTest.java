package com.bancoxyz.bff.web.config;

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
        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) new ConfiguracionGeneral().agregadorWeb(new PropiedadesWeb(new PropiedadesWeb.Agregador(1, 0, 6)));
        try {
            List<CompletableFuture<String>> tareas = IntStream.range(0, 20)
                    .mapToObj(i -> CompletableFuture.supplyAsync(ConfiguracionGeneralTest::dormirYNombrarHilo, pool)).toList();
            List<String> hilos = tareas.stream().map(CompletableFuture::join).toList();
            assertThat(hilos).hasSize(20)
                    .anyMatch(hilo -> hilo.startsWith("agregador-web-"))
                    .anyMatch(hilo -> !hilo.startsWith("agregador-web-"));
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
    @DisplayName("Agregador: con hilos un pool acotado; con 0, en serie en el hilo de la solicitud (solo para medir)")
    void ejecutorDelAgregador() {
        ConfiguracionGeneral configuracion = new ConfiguracionGeneral();
        assertThat(configuracion.agregadorWeb(new PropiedadesWeb(new PropiedadesWeb.Agregador(4, 10, 6))))
                .isInstanceOfSatisfying(ThreadPoolTaskExecutor.class, pool -> {
                    assertThat(pool.getMaxPoolSize()).isEqualTo(4);
                    pool.shutdown();
                });
        assertThat(configuracion.agregadorWeb(new PropiedadesWeb(new PropiedadesWeb.Agregador(0, 10, 6))))
                .isInstanceOf(SyncTaskExecutor.class);
    }
}
