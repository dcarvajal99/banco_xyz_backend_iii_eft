package com.bancoxyz.cuentas.seguridad;

import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Optional;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Segunda credencial: el certificado de cliente de la conexion TLS (mTLS), amarrado al token.
 *
 * <ul>
 *   <li><b>Token de servicio</b>: el CN del certificado debe ser el {@code sub} del token (el id del cliente OAuth). Un
 *       token de bff-cajero robado no sirve sin la clave privada del certificado de bff-cajero.</li>
 *   <li><b>Token de usuario</b>: solo lo puede presentar un BFF autorizado a reenviarlo ({@code relevos-permitidos}:
 *       bff-web y bff-movil). Un token de usuario capturado no se puede usar directo contra el servicio desde afuera.</li>
 * </ul>
 *
 * <p>Va despues de la autenticacion del token y solo en {@code /api/}: con {@code client-auth=want} la conexion TLS se
 * abre sin certificado (Swagger, health) y el rechazo llega como ProblemDetail, no como un handshake cortado.</p>
 */
public class FiltroCertificadoDeLlamante extends OncePerRequestFilter {

    /** Atributo estandar de Servlet con la cadena que presento el cliente. */
    public static final String ATRIBUTO_CERTIFICADOS = "jakarta.servlet.request.X509Certificate";

    private final ProblemasDeSeguridad problemas;
    private final List<String> relevosPermitidos;

    public FiltroCertificadoDeLlamante(ProblemasDeSeguridad problemas, List<String> relevosPermitidos) {
        this.problemas = problemas;
        this.relevosPermitidos = relevosPermitidos;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Sin token no hay nada que amarrar: la autorizacion responde 401 NO_AUTENTICADO.
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token)) {
            chain.doFilter(request, response);
            return;
        }
        Optional<String> nombre = nombreComun(request);
        if (nombre.isEmpty()) {
            problemas.rechazar(request, response, HttpStatus.UNAUTHORIZED, "CERTIFICADO_REQUERIDO",
                    "Certificado de cliente requerido", "El llamante debe presentar su certificado de cliente (mTLS)");
            return;
        }
        IdentidadDelToken quien = IdentidadDelToken.de(token.getToken());
        boolean corresponde = quien.esDeUsuario() ? relevosPermitidos.contains(nombre.get())
                : nombre.get().equals(quien.sujeto());
        if (!corresponde) {
            problemas.rechazar(request, response, HttpStatus.FORBIDDEN, "CERTIFICADO_NO_CORRESPONDE",
                    "Certificado de otro llamante", quien.esDeUsuario()
                            ? "El token de un usuario solo lo puede reenviar un BFF autorizado"
                            : "El certificado presentado no pertenece al cliente OAuth del token");
            return;
        }
        chain.doFilter(request, response);
    }

    /** CN del certificado del cliente (el primero de la cadena), si lo hay y tiene un sujeto legible. */
    public static Optional<String> nombreComun(HttpServletRequest request) {
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
