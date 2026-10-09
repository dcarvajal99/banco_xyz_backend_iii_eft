package com.bancoxyz.bff.cajero.seguridad;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Firma de una solicitud del terminal. La reproducen byte a byte dos piezas escritas por separado —el
 * software del cajero (aqui, scripts/firmar_cajero.sh) y el filtro de este BFF—, por eso cada detalle esta
 * fijado y cubierto por vectores de prueba.
 *
 * <pre>
 * METODO \n RUTA \n MARCA_TIEMPO \n NONCE \n IDEMPOTENCY_KEY \n SHA256_HEX(cuerpo)
 * </pre>
 * <ul>
 *   <li>RUTA: la URI cruda mas "?" y la query cruda solo si existe.</li>
 *   <li>IDEMPOTENCY_KEY vacia si no viene: asi nadie en el camino puede cambiar la clave de un reintento
 *       sin romper la firma y convertirlo en un segundo retiro.</li>
 *   <li>Cuerpo vacio: hash de cero bytes. Sin salto de linea final. Clave: bytes UTF-8 del secreto.
 *       Resultado: Base64 estandar con relleno.</li>
 * </ul>
 */
public final class FirmaHmac {

    private FirmaHmac() {
    }

    public static String canonica(String metodo, String ruta, String marcaTiempo, String nonce,
                                  String claveIdempotencia, byte[] cuerpo) {
        return String.join("\n", metodo, ruta, marcaTiempo, nonce,
                claveIdempotencia == null ? "" : claveIdempotencia, sha256Hex(cuerpo));
    }

    public static byte[] hmac(byte[] secreto, String texto) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secreto, "HmacSHA256"));
            return mac.doFinal(texto.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 no disponible", e);
        }
    }

    public static String firmar(String secreto, String metodo, String ruta, String marcaTiempo, String nonce,
                                String claveIdempotencia, byte[] cuerpo) {
        return Base64.getEncoder().encodeToString(hmac(secreto.getBytes(StandardCharsets.UTF_8),
                canonica(metodo, ruta, marcaTiempo, nonce, claveIdempotencia, cuerpo)));
    }

    static String sha256Hex(byte[] datos) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(datos));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }
}
