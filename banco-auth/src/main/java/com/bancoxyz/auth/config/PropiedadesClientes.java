package com.bancoxyz.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Direccion del core y la credencial de servicio de banco-auth (clave Basic; el certificado va en el bundle TLS). */
@ConfigurationProperties(prefix = "banco.clientes")
public record PropiedadesClientes(String url, String usuario, String clave) {
}
