package com.bancoxyz.bff.cajero.seguridad;

import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.Optional;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Autentica al EQUIPO: la conexion TLS tiene que traer el certificado de cliente del terminal.
 *
 * <p>Cada cajero tiene un certificado emitido por la CA del banco con su id como CN. Tomcat valida la cadena durante el
 * handshake (la CA del banco es la unica confianza); este filtro exige que el certificado exista y que su CN sea el
 * {@code X-Terminal-Id} de la solicitud.</p>
 *
 * <p>No reemplaza a la firma HMAC, la complementa. El certificado prueba que la conexion sale de un equipo del banco
 * (su clave privada no deja el terminal); la firma prueba que el mensaje no se altero y no es una repeticion. Un
 * secreto HMAC copiado no sirve desde otro equipo y un certificado robado no sirve sin el secreto.</p>
 *
 * <p>Si falta {@code X-Terminal-Id} no decide: lo rechaza a continuacion {@link FiltroFirmaDeTerminal} con
 * FIRMA_INVALIDA. No es un {@code @Component} por la misma razon que ese filtro.</p>
 */
public class FiltroCertificadoDeTerminal extends OncePerRequestFilter {

    /** Atributo estandar de Servlet con la cadena que presento el cliente. */
    public static final String ATRIBUTO_CERTIFICADOS = "jakarta.servlet.request.X509Certificate";

    private final RespuestasDeSeguridad respuestas;

    public FiltroCertificadoDeTerminal(RespuestasDeSeguridad respuestas) {
        this.respuestas = respuestas;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/cajero/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<String> terminalDelCertificado = nombreComun(request);
        if (terminalDelCertificado.isEmpty()) {
            respuestas.rechazarTerminal(response, "CERTIFICADO_TERMINAL_REQUERIDO");
            return;
        }
        String declarado = request.getHeader(FiltroFirmaDeTerminal.ENCABEZADO_TERMINAL);
        if (declarado != null && !declarado.equals(terminalDelCertificado.get())) {
            respuestas.rechazarTerminal(response, "CERTIFICADO_NO_CORRESPONDE");
            return;
        }
        chain.doFilter(request, response);
    }

    /** CN del certificado del cliente (el primero de la cadena), si lo hay y tiene un sujeto legible. */
    static Optional<String> nombreComun(HttpServletRequest request) {
        if (!(request.getAttribute(ATRIBUTO_CERTIFICADOS) instanceof X509Certificate[] cadena) || cadena.length == 0) {
            return Optional.empty();
        }
        try {
            return new LdapName(cadena[0].getSubjectX500Principal().getName()).getRdns().stream()
                    .filter(rdn -> "CN".equalsIgnoreCase(rdn.getType()))
                    .map(rdn -> rdn.getValue().toString())
                    .findFirst();
        } catch (InvalidNameException sujetoIlegible) {
            return Optional.empty();
        }
    }
}
