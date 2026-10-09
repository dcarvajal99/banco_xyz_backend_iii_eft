package com.bancoxyz.bff.web.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.web.client.RestClientBuilderConfigurer;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.web.client.RestClient;

/**
 * Descubrimiento del core por Eureka.
 *
 * <p>Con {@code banco.core.descubrimiento=true} (lo activa el Config Server) la URL del core es el nombre del servicio,
 * {@code https://banco-core-api/api/v1}, y en cada llamada Spring Cloud LoadBalancer la reemplaza por una instancia
 * registrada en Eureka (round robin). El BFF no conoce la maquina ni el puerto del core: si el core se mueve o se agrega
 * una instancia, el BFF la encuentra sin cambiar su configuracion.</p>
 *
 * <p>El builder se arma con el configurador de Spring Boot, asi que conserva el certificado de cliente (mTLS) y los
 * timeouts de {@code spring.http.client.*}. Es prototipo, como el de Spring Boot: cada cliente lo modifica.</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "banco.core.descubrimiento", havingValue = "true")
public class DescubrimientoDelCore {

    @Bean
    @LoadBalanced
    @Scope("prototype")
    RestClient.Builder restClientBuilderConDescubrimiento(RestClientBuilderConfigurer configurador) {
        return configurador.configure(RestClient.builder());
    }
}
