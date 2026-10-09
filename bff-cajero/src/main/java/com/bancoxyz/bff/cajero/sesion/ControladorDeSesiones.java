package com.bancoxyz.bff.cajero.sesion;

import com.bancoxyz.bff.cajero.adaptador.AdaptadorCajero;
import com.bancoxyz.bff.cajero.config.FiltroCorrelacion;
import com.bancoxyz.bff.cajero.core.ClienteCoreCajero;
import com.bancoxyz.bff.cajero.core.ErrorDelCore;
import com.bancoxyz.bff.cajero.core.TarjetaAutenticadaCore;
import com.bancoxyz.bff.cajero.error.Acciones;
import com.bancoxyz.bff.cajero.error.ErrorDeCajero;
import com.bancoxyz.bff.cajero.error.TraductorDeErroresDelCore;
import com.bancoxyz.bff.cajero.seguridad.EmisorDeSesiones;
import com.bancoxyz.bff.cajero.seguridad.EmisorDeSesiones.SesionEmitida;
import com.bancoxyz.bff.cajero.seguridad.FiltroFirmaDeTerminal;
import com.bancoxyz.bff.cajero.seguridad.RegistroDeRevocaciones;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;

@RestController
@RequestMapping("/cajero/v1/sesiones")
public class ControladorDeSesiones {

    private final ClienteCoreCajero core;
    private final EmisorDeSesiones emisor;
    private final RegistroDeRevocaciones revocaciones;
    private final TraductorDeErroresDelCore traductor;

    public ControladorDeSesiones(ClienteCoreCajero core, EmisorDeSesiones emisor, RegistroDeRevocaciones revocaciones,
                                 TraductorDeErroresDelCore traductor) {
        this.core = core;
        this.emisor = emisor;
        this.revocaciones = revocaciones;
        this.traductor = traductor;
    }

    @Operation(summary = "Inicia una sesion con tarjeta y PIN (solicitud firmada por el terminal)")
    @PostMapping
    public ResponseEntity<RespuestaSesion> iniciar(@RequestBody(required = false) SolicitudSesion solicitud,
                                                   @RequestHeader(FiltroFirmaDeTerminal.ENCABEZADO_TERMINAL) String terminal) {
        // El formato se valida aqui y no se le pregunta al core: un PAN mal digitado no debe gastar un intento de PIN.
        if (solicitud == null || solicitud.numeroTarjeta() == null || !solicitud.numeroTarjeta().matches("\\d{16}")
                || !Luhn.esValido(solicitud.numeroTarjeta()) || solicitud.pin() == null || !solicitud.pin().matches("\\d{4}")) {
            throw new ErrorDeCajero(HttpStatus.BAD_REQUEST, "DATOS_TARJETA_INVALIDOS", "Datos de tarjeta inválidos.",
                    Acciones.DEVOLVER_TARJETA);
        }
        TarjetaAutenticadaCore tarjeta;
        try {
            tarjeta = core.autenticarTarjeta(solicitud.numeroTarjeta(), solicitud.pin(), FiltroCorrelacion.actual());
        } catch (ErrorDelCore | RestClientException error) {
            throw traductor.enSesion(error);
        }
        SesionEmitida sesion = emisor.emitir(tarjeta, terminal);
        return ResponseEntity.status(HttpStatus.CREATED).body(new RespuestaSesion(sesion.token(), sesion.expiraEn(),
                AdaptadorCajero.titularAbreviado(tarjeta.titular()), Acciones.MOSTRAR_MENU));
    }

    @Operation(summary = "Termina la sesion (el cliente retira la tarjeta)")
    @DeleteMapping
    public RespuestaFinDeSesion terminar(@AuthenticationPrincipal Jwt jwt) {
        revocaciones.revocar(jwt.getId(), jwt.getExpiresAt());
        return new RespuestaFinDeSesion(Acciones.DEVOLVER_TARJETA, "Gracias por usar nuestros cajeros. Retire su tarjeta.");
    }
}
