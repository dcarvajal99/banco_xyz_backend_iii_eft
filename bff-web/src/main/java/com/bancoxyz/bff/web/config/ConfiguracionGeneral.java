package com.bancoxyz.bff.web.config;

import java.time.Clock;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskDecorator;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class ConfiguracionGeneral {

    @Bean
    public Clock reloj() {
        return Clock.system(ZoneId.of("America/Santiago"));
    }

    /**
     * Hilos del agregador del panel.
     *
     * <p>El decorador copia SOLO el MDC, para que los logs de las tareas lleven la correlacion. La identidad del
     * usuario NO se propaga por el hilo: viaja como parametro. Un pool reutiliza hilos, y un contexto de seguridad
     * heredado (MODE_INHERITABLETHREADLOCAL) quedaria pegado al primer usuario que creo el hilo: el core, que confia
     * en X-Usuario-Id, le entregaria a John las cuentas de Jane.</p>
     *
     * <p>Saturado no rechaza: si no hay un hilo libre (y la cola esta llena, o es 0), la tarea corre en el hilo de la
     * solicitud que la pidio. La medicion de la semana 5 mostro que con 8 paneles a la vez el rechazo por defecto
     * (TaskRejectedException) devolvia 500; con CallerRunsPolicy la carga extra frena a quien la genera.</p>
     */
    @Bean
    public TaskExecutor agregadorWeb(PropiedadesWeb propiedades) {
        if (propiedades.agregador().hilos() <= 0) {
            // 0 existe solo para medir cuanto aporta el paralelismo (scripts/medir.sh): en serie, en el hilo de la solicitud.
            return new SyncTaskExecutor();
        }
        ThreadPoolTaskExecutor ejecutor = new ThreadPoolTaskExecutor();
        ejecutor.setCorePoolSize(propiedades.agregador().hilos());
        ejecutor.setMaxPoolSize(propiedades.agregador().hilos());
        ejecutor.setQueueCapacity(propiedades.agregador().cola());
        ejecutor.setThreadNamePrefix("agregador-web-");
        ejecutor.setTaskDecorator(copiarMdc());
        ejecutor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        ejecutor.initialize();
        return ejecutor;
    }

    static TaskDecorator copiarMdc() {
        return tarea -> {
            Map<String, String> mdc = MDC.getCopyOfContextMap();
            return () -> {
                try {
                    if (mdc != null) {
                        MDC.setContextMap(mdc);
                    }
                    tarea.run();
                } finally {
                    MDC.clear();
                }
            };
        };
    }

    @Bean
    public OpenAPI contratoOpenApi() {
        return new OpenAPI()
                .info(new Info().title("Banco XYZ - BFF Web").version("1.0.0")
                        .description("Backend para el navegador: banca en linea del cliente y consola del ejecutivo. "
                                + "Datos completos, paginacion con metadatos, series para graficos y un panel agregado. "
                                + "Sesion de servidor con cookie HttpOnly BFFWEB_SESION; los metodos que modifican exigen "
                                + "el token CSRF (cookie XSRF-TOKEN reenviada en el encabezado X-XSRF-TOKEN)."))
                .components(new Components()
                        .addSecuritySchemes("sesionDelNavegador", new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.COOKIE).name("BFFWEB_SESION"))
                        .addSecuritySchemes("tokenCsrf", new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER).name("X-XSRF-TOKEN")))
                .addSecurityItem(new SecurityRequirement().addList("sesionDelNavegador"));
    }
}
