package com.bancoxyz.cuentas.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.security.cert.Certificate;
import java.security.cert.X509Certificate;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;
import javax.security.auth.x500.X500Principal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verificacion TLS hacia las replicas de clientes-service, llamadas por su IP de Eureka. */
class TlsHaciaReplicasTest {

    private final HostnameVerifier verificador = TlsHaciaReplicas.verificador();

    @Test
    @DisplayName("Una IP con el certificado de clientes-service se acepta: es una replica del servicio que se llama")
    void ipConElCertificadoEsperado() throws Exception {
        assertThat(verificador.verify("172.30.0.9", sesionCon("clientes-service"))).isTrue();
    }

    @Test
    @DisplayName("Una IP con el certificado de otro servicio del banco se rechaza")
    void ipConOtroCertificado() throws Exception {
        assertThat(verificador.verify("172.30.0.9", sesionCon("pagos-service"))).isFalse();
    }

    @Test
    @DisplayName("Un nombre que no coincide con el certificado se rechaza como siempre")
    void nombreQueNoCoincide() throws Exception {
        assertThat(verificador.verify("otro-servicio", sesionCon("clientes-service"))).isFalse();
    }

    private static SSLSession sesionCon(String nombreComun) throws Exception {
        X509Certificate certificado = mock(X509Certificate.class);
        when(certificado.getSubjectX500Principal()).thenReturn(new X500Principal("CN=" + nombreComun));
        SSLSession sesion = mock(SSLSession.class);
        when(sesion.getPeerCertificates()).thenReturn(new Certificate[] {certificado});
        return sesion;
    }
}
