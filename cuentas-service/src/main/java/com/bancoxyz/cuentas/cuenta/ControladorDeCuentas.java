package com.bancoxyz.cuentas.cuenta;

import java.net.URI;
import java.util.List;

import com.bancoxyz.cuentas.seguridad.ControlDeAcceso;
import com.bancoxyz.cuentas.seguridad.IdentidadDelToken;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ControladorDeCuentas {

    private final ServicioDeCuentas servicio;

    public ControladorDeCuentas(ServicioDeCuentas servicio) {
        this.servicio = servicio;
    }

    @Operation(summary = "Cuentas de un cliente: el propio cliente o un ejecutivo (cuentas.leer)")
    @GetMapping("/clientes/{clienteId}/cuentas")
    public ResponseEntity<List<CuentaDto>> cuentasDeCliente(@PathVariable long clienteId, Authentication autenticacion) {
        return ResponseEntity.ok(servicio.cuentasDeCliente(IdentidadDelToken.de(autenticacion), clienteId));
    }

    @Operation(summary = "Una cuenta (cuentas.leer por usuario o servicio; cajero.operar por tarjeta)")
    @GetMapping("/cuentas/{cuentaId}")
    public ResponseEntity<CuentaDto> cuenta(@PathVariable long cuentaId,
                                            @RequestHeader(value = ControlDeAcceso.ENCABEZADO_TARJETA, required = false) String tarjetaId,
                                            @RequestHeader(value = ControlDeAcceso.ENCABEZADO_TERMINAL, required = false) String terminalId,
                                            Authentication autenticacion) {
        return ResponseEntity.ok(servicio.cuenta(IdentidadDelToken.de(autenticacion), tarjetaId, terminalId, cuentaId));
    }

    @Operation(summary = "Movimientos del cierre y en linea, paginados")
    @GetMapping("/cuentas/{cuentaId}/movimientos")
    public ResponseEntity<PaginaMovimientos> movimientos(@PathVariable long cuentaId,
                                                         @RequestParam(defaultValue = "0") int pagina,
                                                         @RequestParam(defaultValue = "20") int tamano,
                                                         Authentication autenticacion) {
        return ResponseEntity.ok(servicio.movimientos(IdentidadDelToken.de(autenticacion), cuentaId, pagina, tamano));
    }

    @Operation(summary = "Estados de cuenta anuales del cierre publicado")
    @GetMapping("/cuentas/{cuentaId}/estados-anuales")
    public ResponseEntity<List<EstadoAnualDto>> estadosAnuales(@PathVariable long cuentaId, Authentication autenticacion) {
        return ResponseEntity.ok(servicio.estadosAnuales(IdentidadDelToken.de(autenticacion), cuentaId));
    }

    @Operation(summary = "Apertura de una cuenta (ejecutivo; cuentas.administrar)")
    @PostMapping("/cuentas")
    public ResponseEntity<CuentaDto> abrir(@Valid @RequestBody SolicitudApertura solicitud, Authentication autenticacion) {
        CuentaDto cuenta = servicio.abrir(IdentidadDelToken.de(autenticacion), solicitud);
        return ResponseEntity.created(URI.create("/api/v1/cuentas/" + cuenta.cuentaId())).body(cuenta);
    }

    @Operation(summary = "Mantenimiento: alias y bloqueo/desbloqueo (ejecutivo; cuentas.administrar)")
    @PatchMapping("/cuentas/{cuentaId}")
    public ResponseEntity<CuentaDto> mantener(@PathVariable long cuentaId,
                                              @Valid @RequestBody SolicitudMantenimiento solicitud,
                                              Authentication autenticacion) {
        return ResponseEntity.ok(servicio.mantener(IdentidadDelToken.de(autenticacion), cuentaId, solicitud));
    }

    @Operation(summary = "Cierre de una cuenta con saldo cero (ejecutivo; cuentas.administrar)")
    @PostMapping("/cuentas/{cuentaId}/cierre")
    public ResponseEntity<CuentaDto> cerrar(@PathVariable long cuentaId, @Valid @RequestBody SolicitudCierre solicitud,
                                            Authentication autenticacion) {
        return ResponseEntity.ok(servicio.cerrar(IdentidadDelToken.de(autenticacion), cuentaId, solicitud));
    }
}
