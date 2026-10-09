package com.bancoxyz.auth.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.web.client.RestClientBuilderConfigurer;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.web.client.RestClient;

/**
 * Con {@code banco.clientes.descubrimiento=true} (lo activa el Config Server) el core se busca por nombre en Eureka en cada
 * llamada. El builder se arma con el configurador de Spring Boot: conserva el certificado de cliente y los timeouts.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "banco.clientes.descubrimiento", havingValue = "true")
public class Descubrimiento {

    @Bean
    @LoadBalanced
    @Scope("prototype")
    RestClient.Builder restClientBuilderConDescubrimiento(RestClientBuilderConfigurer configurador) {
        return configurador.configure(RestClient.builder());
    }
}
