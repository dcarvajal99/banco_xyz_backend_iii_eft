package com.bancoxyz.bff.movil.seguridad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import com.bancoxyz.bff.movil.config.PropiedadesMovil;
import com.bancoxyz.bff.movil.error.ErrorMovil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

class TokensYAlmacenTest {

    private final PropiedadesMovil propiedades = new PropiedadesMovil(
            new PropiedadesMovil.Jwt("movil-jwt-secreto-de-desarrollo-cambiar-en-prod-0001"), 300, 7, 10, 8);
    private final ConfiguracionSeguridad configuracion = new ConfiguracionSeguridad();

    @Test
    @DisplayName("El access token lleva emisor, audiencia, cliente, dispositivo, familia y vive 5 minutos")
    void reclamosDelAccessToken() {
        var clave = configuracion.claveMovil(propiedades);
        EmisorDeTokens emisor = new EmisorDeTokens(configuracion.codificadorMovil(clave),
                new AlmacenDeRefresh(Clock.systemUTC(), propiedades), Clock.systemUTC(), propiedades);
        EmisorDeTokens.ParDeTokens par = emisor.emitir(3, 6, "Jane Smith", "iphone-1", "familia-1");
        JwtDecoder decodificador = configuracion.decodificadorMovil(clave);
        Jwt jwt = decodificador.decode(par.accessToken());
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("bff-movil");
        assertThat(jwt.getAudience()).containsExactly("app-movil");
        UsuarioMovil usuario = UsuarioMovil.desde(jwt);
        assertThat(usuario).isEqualTo(new UsuarioMovil(3, 6, "Jane Smith", "iphone-1", "familia-1"));
        assertThat(jwt.getExpiresAt().getEpochSecond() - jwt.getIssuedAt().getEpochSecond()).isEqualTo(300);
        assertThat(jwt.getId()).isNotBlank();
    }

    @Test
    @DisplayName("Un secreto de menos de 32 bytes se rechaza al arrancar")
    void secretoCorto() {
        assertThatThrownBy(() -> configuracion.claveMovil(new PropiedadesMovil(new PropiedadesMovil.Jwt("corto"), 300, 7, 10, 8)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Un refresh token vencido o nulo no renueva")
    void refreshVencido() {
        Instant[] ahora = {Instant.parse("2026-09-12T20:00:00Z")};
        Clock reloj = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneId.of("UTC");
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return ahora[0];
            }
        };
        AlmacenDeRefresh almacen = new AlmacenDeRefresh(reloj, propiedades);
        String token = almacen.emitir(3, 6, "Jane", "iphone-1", "f1");
        ahora[0] = ahora[0].plusSeconds(8 * 24 * 3600);
        assertThatThrownBy(() -> almacen.consumir(token, "iphone-1")).isInstanceOf(ErrorMovil.class)
                .extracting("codigo").isEqualTo("REFRESH_INVALIDO");
        assertThatThrownBy(() -> almacen.consumir(null, "iphone-1")).isInstanceOf(ErrorMovil.class);
        almacen.revocarFamilia(null);
        assertThat(almacen.familiaRevocada(null)).isFalse();
    }
}
