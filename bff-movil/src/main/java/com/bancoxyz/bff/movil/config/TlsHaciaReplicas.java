package com.bancoxyz.bff.movil.config;

import java.security.cert.X509Certificate;
import java.util.Set;
import java.util.regex.Pattern;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;

import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.client5.http.ssl.DefaultHostnameVerifier;
import org.apache.hc.client5.http.ssl.HostnameVerificationPolicy;
import org.apache.hc.core5.reactor.ssl.SSLBufferMode;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * TLS hacia las replicas de los microservicios. LoadBalancer llama a la IP de cada replica registrada en Eureka, y el
 * certificado de un servicio no puede listar IPs de contenedor que cambian. Primero se verifica el nombre como siempre;
 * si el destino es una IP, se acepta solo si presenta el certificado de un servicio que esta aplicacion llama (la cadena
 * de la CA del banco ya la valido el truststore: una CA externa no llega hasta aqui).
 */
@Configuration(proxyBeanMethods = false)
public class TlsHaciaReplicas {

    /** CN de los servicios a los que esta aplicacion llama por Eureka. */
    static final Set<String> SERVIDORES = Set.of("cuentas-service", "pagos-service");

    private static final Pattern IP = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}|.*:.*");

    @Bean
    ClientHttpRequestFactoryBuilder<?> fabricaHttpHaciaReplicas() {
        return ClientHttpRequestFactoryBuilder.httpComponents().withTlsSocketStrategyFactory(paquete -> paquete == null
                ? DefaultClientTlsStrategy.createSystemDefault()
                : new DefaultClientTlsStrategy(paquete.createSslContext(), paquete.getOptions().getEnabledProtocols(),
                        paquete.getOptions().getCiphers(), SSLBufferMode.STATIC, HostnameVerificationPolicy.CLIENT,
                        verificador()));
    }

    static HostnameVerifier verificador() {
        DefaultHostnameVerifier estandar = new DefaultHostnameVerifier();
        return (host, sesion) -> estandar.verify(host, sesion)
                || (IP.matcher(host).matches() && SERVIDORES.contains(nombreComun(sesion)));
    }

    static String nombreComun(SSLSession sesion) {
        try {
            X509Certificate certificado = (X509Certificate) sesion.getPeerCertificates()[0];
            return new LdapName(certificado.getSubjectX500Principal().getName()).getRdns().stream()
                    .filter(rdn -> "CN".equalsIgnoreCase(rdn.getType())).map(rdn -> rdn.getValue().toString())
                    .findFirst().orElse("");
        } catch (SSLPeerUnverifiedException | InvalidNameException | ClassCastException e) {
            return "";
        }
    }
}
