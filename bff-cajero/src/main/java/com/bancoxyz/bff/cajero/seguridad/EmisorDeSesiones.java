package com.bancoxyz.bff.cajero.seguridad;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.bancoxyz.bff.cajero.config.PropiedadesCajero;
import com.bancoxyz.bff.cajero.core.TarjetaAutenticadaCore;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/**
 * Emite la sesion de tarjeta: 120 segundos, ligada al terminal y a la cuenta de la tarjeta.
 *
 * <p>El secreto, el emisor y la audiencia son propios del canal cajero: un token del BFF movil nunca
 * valida aqui, ni uno de aqui en el movil.</p>
 */
@Component
public class EmisorDeSesiones {

    public static final String EMISOR = "bff-cajero";
    public static final String AUDIENCIA = "cajero";

    public record SesionEmitida(String token, long expiraEn) {
    }

    private final JwtEncoder codificador;
    private final Clock reloj;
    private final long vidaSegundos;

    public EmisorDeSesiones(JwtEncoder codificador, Clock reloj, PropiedadesCajero propiedades) {
        this.codificador = codificador;
        this.reloj = reloj;
        this.vidaSegundos = propiedades.sesionSegundos();
    }

    public SesionEmitida emitir(TarjetaAutenticadaCore tarjeta, String terminal) {
        Instant ahora = reloj.instant();
        JwtClaimsSet reclamos = JwtClaimsSet.builder()
                .issuer(EMISOR)
                .audience(List.of(AUDIENCIA))
                .subject(String.valueOf(tarjeta.tarjetaId()))
                .issuedAt(ahora)
                .expiresAt(ahora.plusSeconds(vidaSegundos))
                .id(UUID.randomUUID().toString())
                .claim("cuenta", tarjeta.cuentaId())
                .claim("terminal", terminal)
                .claim("ultimos4", tarjeta.ultimos4())
                .build();
        // Cabecera HS256 explicita: sin ella NimbusJwtEncoder no sabe con que algoritmo firmar un secreto.
        String token = codificador.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), reclamos))
                .getTokenValue();
        return new SesionEmitida(token, vidaSegundos);
    }
}
