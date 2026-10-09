package com.bancoxyz.bff.cajero.operacion;

import java.math.BigDecimal;

import com.bancoxyz.bff.cajero.adaptador.AdaptadorCajero;
import com.bancoxyz.bff.cajero.config.FiltroCorrelacion;
import com.bancoxyz.bff.cajero.config.PropiedadesCajero;
import com.bancoxyz.bff.cajero.core.ClienteCoreCajero;
import com.bancoxyz.bff.cajero.core.ClienteCoreCajero.RespuestaRetiro;
import com.bancoxyz.bff.cajero.core.CuentaCore;
import com.bancoxyz.bff.cajero.core.ErrorDelCore;
import com.bancoxyz.bff.cajero.error.Acciones;
import com.bancoxyz.bff.cajero.error.ErrorDeCajero;
import com.bancoxyz.bff.cajero.error.TraductorDeErroresDelCore;
import com.bancoxyz.bff.cajero.seguridad.FiltroFirmaDeTerminal;
import com.bancoxyz.bff.cajero.sesion.SesionDeTarjeta;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;

@RestController
@RequestMapping("/cajero/v1")
public class ControladorDeOperaciones {

    private final ClienteCoreCajero core;
    private final AdaptadorCajero adaptador;
    private final TraductorDeErroresDelCore traductor;
    private final PropiedadesCajero propiedades;

    public ControladorDeOperaciones(ClienteCoreCajero core, AdaptadorCajero adaptador,
                                    TraductorDeErroresDelCore traductor, PropiedadesCajero propiedades) {
        this.core = core;
        this.adaptador = adaptador;
        this.traductor = traductor;
        this.propiedades = propiedades;
    }

    @Operation(summary = "Consulta de saldo de la cuenta de la tarjeta")
    @GetMapping("/saldo")
    public PantallaSaldo saldo(@AuthenticationPrincipal Jwt jwt,
                               @RequestHeader(FiltroFirmaDeTerminal.ENCABEZADO_TERMINAL) String terminal) {
        SesionDeTarjeta sesion = SesionDeTarjeta.desde(jwt);
        CuentaCore cuenta;
        try {
            cuenta = core.cuenta(sesion.cuentaId(), sesion.tarjetaId(), terminal, FiltroCorrelacion.actual());
        } catch (ErrorDelCore | RestClientException error) {
            throw traductor.enConsulta(error);
        }
        return adaptador.saldo(cuenta);
    }

    @Operation(summary = "Retiro de efectivo, idempotente por Idempotency-Key")
    @PostMapping("/retiros")
    public ResponseEntity<ComprobanteRetiro> retirar(@AuthenticationPrincipal Jwt jwt,
                                                     @RequestHeader(FiltroFirmaDeTerminal.ENCABEZADO_TERMINAL) String terminal,
                                                     @RequestHeader(value = ClienteCoreCajero.ENCABEZADO_IDEMPOTENCIA, required = false) String clave,
                                                     @RequestBody(required = false) SolicitudRetiro solicitud) {
        if (clave == null || clave.length() < 8 || clave.length() > 80) {
            throw new ErrorDeCajero(HttpStatus.BAD_REQUEST, "CLAVE_IDEMPOTENCIA_REQUERIDA", "Operación no válida.",
                    Acciones.NINGUNA);
        }
        BigDecimal monto = validarMonto(solicitud == null ? null : solicitud.monto());
        SesionDeTarjeta sesion = SesionDeTarjeta.desde(jwt);
        RespuestaRetiro respuesta;
        try {
            respuesta = core.retirar(sesion.cuentaId(), sesion.tarjetaId(), terminal, clave, monto,
                    FiltroCorrelacion.actual());
        } catch (ErrorDelCore | RestClientException error) {
            throw traductor.enRetiro(error);
        }
        ComprobanteRetiro comprobante = adaptador.comprobante(respuesta.operacion());
        if (respuesta.repetida()) {
            // El terminal reintenta solo si no recibio la respuesta, es decir, si no dispenso: se le repite la orden.
            return ResponseEntity.ok().header(ClienteCoreCajero.ENCABEZADO_REPETIDA, "true").body(comprobante);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(comprobante);
    }

    /** Multiplos de $1.000 porque el dispensador tiene billetes de $1.000: regla del hardware, no del dinero. */
    private BigDecimal validarMonto(BigDecimal monto) {
        BigDecimal multiplo = propiedades.multiploBilletes();
        if (monto == null || monto.signum() <= 0 || monto.remainder(multiplo).signum() != 0) {
            throw new ErrorDeCajero(HttpStatus.BAD_REQUEST, "MONTO_INVALIDO",
                    "Ingrese un monto múltiplo de " + AdaptadorCajero.pesos(multiplo) + ".", Acciones.MOSTRAR_MENU);
        }
        if (monto.compareTo(propiedades.maximoPorRetiro()) > 0) {
            throw new ErrorDeCajero(HttpStatus.BAD_REQUEST, "MONTO_INVALIDO",
                    "El monto máximo por retiro es " + AdaptadorCajero.pesos(propiedades.maximoPorRetiro()) + ".",
                    Acciones.MOSTRAR_MENU);
        }
        return monto;
    }
}
