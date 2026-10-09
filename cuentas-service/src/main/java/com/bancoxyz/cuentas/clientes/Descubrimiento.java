package com.bancoxyz.cuentas.clientes;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.web.client.RestClientBuilderConfigurer;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.web.client.RestClient;

/**
 * Con {@code banco.descubrimiento=true} (lo entrega el Config Server) clientes-service se busca por nombre en Eureka y
 * Spring Cloud LoadBalancer reparte las llamadas entre sus instancias. Conserva el certificado de cliente y los timeouts.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "banco.descubrimiento", havingValue = "true")
public class Descubrimiento {

    @Bean
    @LoadBalanced
    @Scope("prototype")
    RestClient.Builder restClientBuilderConDescubrimiento(RestClientBuilderConfigurer configurador) {
        return configurador.configure(RestClient.builder());
    }
}
