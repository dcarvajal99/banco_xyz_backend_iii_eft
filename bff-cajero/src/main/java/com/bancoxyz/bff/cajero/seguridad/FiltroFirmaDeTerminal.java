package com.bancoxyz.bff.cajero.seguridad;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import com.bancoxyz.bff.cajero.config.PropiedadesCajero;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Autentica al TERMINAL en cada solicitud: prueba que viene de un cajero del banco y no de alguien con curl.
 *
 * <h2>Orden de las comprobaciones</h2>
 * <ol>
 *   <li>Encabezados presentes y terminal conocido.</li>
 *   <li>Marca de tiempo dentro de +-30 s: una solicitud capturada deja de servir en medio minuto.</li>
 *   <li>Firma, comparada en tiempo constante ({@link MessageDigest#isEqual}) para no filtrar por tiempo cuantos
 *       bytes coinciden.</li>
 *   <li>Recien entonces se registra el nonce. Si se registrara antes de validar la firma, cualquiera podria
 *       "quemar" nonces ajenos mandando solicitudes con firmas basura.</li>
 * </ol>
 *
 * <p>Los nonces se guardan en memoria el doble de la ventana y luego se desalojan: pasada la ventana, la marca
 * de tiempo ya rechaza la repeticion. Con varias instancias del BFF habria que llevarlos a un almacen
 * compartido (limitacion documentada).</p>
 *
 * <p>No es un {@code @Component}: se instancia en la cadena de seguridad para que Boot no lo registre
 * ademas como filtro de servlet y lo ejecute dos veces.</p>
 */
public class FiltroFirmaDeTerminal extends OncePerRequestFilter {

    public static final String ENCABEZADO_TERMINAL = "X-Terminal-Id";
    public static final String ENCABEZADO_MARCA = "X-Marca-Tiempo";
    public static final String ENCABEZADO_NONCE = "X-Nonce";
    public static final String ENCABEZADO_FIRMA = "X-Firma";
    public static final String ENCABEZADO_IDEMPOTENCIA = "Idempotency-Key";

    private final Map<String, byte[]> secretos;
    private final Clock reloj;
    private final long ventanaSegundos;
    private final RespuestasDeSeguridad respuestas;
    private final Map<String, Long> noncesVistos = new ConcurrentHashMap<>();
    private final AtomicLong ultimoDesalojo = new AtomicLong();

    public FiltroFirmaDeTerminal(PropiedadesCajero propiedades, Clock reloj, RespuestasDeSeguridad respuestas) {
        this.secretos = propiedades.terminales().stream().collect(Collectors.toUnmodifiableMap(
                PropiedadesCajero.Terminal::id, t -> t.secreto().getBytes(StandardCharsets.UTF_8)));
        this.reloj = reloj;
        this.ventanaSegundos = propiedades.ventanaFirmaSegundos();
        this.respuestas = respuestas;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/cajero/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        byte[] cuerpo = request.getInputStream().readAllBytes();
        String terminal = request.getHeader(ENCABEZADO_TERMINAL);
        String marca = request.getHeader(ENCABEZADO_MARCA);
        String nonce = request.getHeader(ENCABEZADO_NONCE);
        String firma = request.getHeader(ENCABEZADO_FIRMA);
        byte[] secreto = terminal == null ? null : secretos.get(terminal);
        if (secreto == null || marca == null || nonce == null || firma == null
                || nonce.length() < 16 || nonce.length() > 64) {
            respuestas.rechazarTerminal(response, "FIRMA_INVALIDA");
            return;
        }
        long segundos;
        try {
            segundos = Long.parseLong(marca);
        } catch (NumberFormatException e) {
            respuestas.rechazarTerminal(response, "FIRMA_INVALIDA");
            return;
        }
        long ahora = reloj.instant().getEpochSecond();
        if (Math.abs(ahora - segundos) > ventanaSegundos) {
            respuestas.rechazarTerminal(response, "MARCA_TIEMPO_FUERA_DE_VENTANA");
            return;
        }
        String ruta = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        byte[] esperada = FirmaHmac.hmac(secreto, FirmaHmac.canonica(request.getMethod(), ruta, marca, nonce,
                request.getHeader(ENCABEZADO_IDEMPOTENCIA), cuerpo));
        byte[] recibida;
        try {
            recibida = Base64.getDecoder().decode(firma);
        } catch (IllegalArgumentException e) {
            respuestas.rechazarTerminal(response, "FIRMA_INVALIDA");
            return;
        }
        if (!MessageDigest.isEqual(esperada, recibida)) {
            respuestas.rechazarTerminal(response, "FIRMA_INVALIDA");
            return;
        }
        desalojarNoncesViejos(ahora);
        if (noncesVistos.putIfAbsent(terminal + "|" + nonce, ahora) != null) {
            respuestas.rechazarTerminal(response, "NONCE_REPETIDO");
            return;
        }
        chain.doFilter(new SolicitudConCuerpo(request, cuerpo), response);
    }

    private void desalojarNoncesViejos(long ahora) {
        long anterior = ultimoDesalojo.get();
        if (ahora > anterior && ultimoDesalojo.compareAndSet(anterior, ahora)) {
            long limite = ahora - 2 * ventanaSegundos;
            noncesVistos.values().removeIf(visto -> visto < limite);
        }
    }

    int noncesRegistrados() {
        return noncesVistos.size();
    }
}
