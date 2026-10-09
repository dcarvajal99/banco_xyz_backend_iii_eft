package com.bancoxyz.bff.movil.config;

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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

@Configuration
public class ConfiguracionGeneral {

    @Bean
    public Clock reloj() {
        return Clock.system(ZoneId.of("America/Santiago"));
    }

    /**
     * Hilos con que la portada pide al core, en paralelo, los movimientos de cada cuenta: la portada tarda lo que la
     * cuenta mas lenta y no la suma de todas.
     *
     * <p>El decorador copia SOLO el MDC (la correlacion de los logs). La identidad del usuario viaja como parametro:
     * un hilo reutilizado no puede quedar con el contexto de seguridad de otro usuario.</p>
     *
     * <p>Sin cola y con CallerRunsPolicy: saturado, la tarea corre en el hilo de la solicitud; nunca se rechaza.</p>
     */
    @Bean
    public TaskExecutor portadaMovil(PropiedadesMovil propiedades) {
        if (propiedades.hilosPortada() <= 0) {
            // 0 existe solo para medir cuanto aporta el paralelismo (scripts/medir.sh): en serie, en el hilo de la solicitud.
            return new SyncTaskExecutor();
        }
        ThreadPoolTaskExecutor ejecutor = new ThreadPoolTaskExecutor();
        ejecutor.setCorePoolSize(propiedades.hilosPortada());
        ejecutor.setMaxPoolSize(propiedades.hilosPortada());
        ejecutor.setQueueCapacity(0);
        ejecutor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        ejecutor.setThreadNamePrefix("portada-movil-");
        ejecutor.setTaskDecorator(tarea -> {
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
        });
        ejecutor.initialize();
        return ejecutor;
    }

    /**
     * ETag en las respuestas GET de la app. Con {@code If-None-Match} la app recibe 304 sin cuerpo si la
     * portada no cambio: cero bytes de datos moviles.
     *
     * <p>ETag DEBIL a proposito: Tomcat reescribe un ETag fuerte como debil al comprimir, y entonces el
     * {@code If-None-Match} que devuelve el cliente ya no coincidiria con uno fuerte y el 304 nunca llegaria.</p>
     *
     * <p>Se puede apagar ({@code banco.movil.etag.habilitado=false}) solo para medir cuanto aporta.</p>
     */
    @Bean
    @ConditionalOnProperty(prefix = "banco.movil.etag", name = "habilitado", havingValue = "true", matchIfMissing = true)
    public FilterRegistrationBean<ShallowEtagHeaderFilter> etagDeLaApp() {
        ShallowEtagHeaderFilter filtro = new ShallowEtagHeaderFilter();
        filtro.setWriteWeakETag(true);
        FilterRegistrationBean<ShallowEtagHeaderFilter> registro = new FilterRegistrationBean<>(filtro);
        registro.addUrlPatterns("/movil/v1/*");
        registro.setName("etagDeLaApp");
        return registro;
    }

    @Bean
    public OpenAPI contratoOpenApi() {
        return new OpenAPI()
                .info(new Info().title("Banco XYZ - BFF App Movil").version("1.0.0")
                        .description("Backend para la app movil: respuestas ligeras (sin nulos, comprimidas, con ETag y "
                                + "paginacion por cursor). Autenticacion con access token JWT de 5 minutos ligado al "
                                + "dispositivo (X-Dispositivo-Id) y refresh token rotativo con deteccion de reutilizacion."))
                .components(new Components().addSecuritySchemes("tokenMovil", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
                        .description("Enviar tambien X-Dispositivo-Id")))
                .addSecurityItem(new SecurityRequirement().addList("tokenMovil"));
    }
}
