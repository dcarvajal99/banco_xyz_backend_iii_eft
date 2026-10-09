package com.bancoxyz.bff.movil.seguridad;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.bancoxyz.bff.movil.config.PropiedadesMovil;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/** Emite el par access + refresh del canal movil. */
@Component
public class EmisorDeTokens {

    public static final String EMISOR = "bff-movil";
    public static final String AUDIENCIA = "app-movil";

    public record ParDeTokens(String accessToken, String refreshToken, String tipo, long expiraEn) {
    }

    private final JwtEncoder codificador;
    private final AlmacenDeRefresh refresh;
    private final Clock reloj;
    private final long vidaAccess;

    public EmisorDeTokens(JwtEncoder codificador, AlmacenDeRefresh refresh, Clock reloj, PropiedadesMovil propiedades) {
        this.codificador = codificador;
        this.refresh = refresh;
        this.reloj = reloj;
        this.vidaAccess = propiedades.accessSegundos();
    }

    public ParDeTokens emitir(long usuarioId, long clienteId, String nombre, String dispositivo, String familia) {
        Instant ahora = reloj.instant();
        JwtClaimsSet reclamos = JwtClaimsSet.builder()
                .issuer(EMISOR)
                .audience(List.of(AUDIENCIA))
                .subject(String.valueOf(usuarioId))
                .issuedAt(ahora)
                .expiresAt(ahora.plusSeconds(vidaAccess))
                .id(UUID.randomUUID().toString())
                .claim("cliente", clienteId)
                .claim("nombre", nombre)
                .claim("dispositivo", dispositivo)
                .claim("fam", familia)
                .build();
        String access = codificador.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), reclamos))
                .getTokenValue();
        return new ParDeTokens(access, refresh.emitir(usuarioId, clienteId, nombre, dispositivo, familia), "Bearer",
                vidaAccess);
    }

    public static String nuevaFamilia() {
        return UUID.randomUUID().toString();
    }
}
