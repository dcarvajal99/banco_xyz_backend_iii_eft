package com.bancoxyz.bff.web.ejecutivo;

import java.time.LocalDate;

import com.bancoxyz.bff.web.adaptador.AdaptadorWeb;
import com.bancoxyz.bff.web.adaptador.CalidadWeb;
import com.bancoxyz.bff.web.adaptador.ResumenDiarioWeb;
import com.bancoxyz.bff.web.config.FiltroCorrelacion;
import com.bancoxyz.bff.web.core.ClienteCoreWeb;
import com.bancoxyz.bff.web.core.ClienteDelBancoCore;
import com.bancoxyz.bff.web.error.ErrorWeb;
import com.bancoxyz.bff.web.panel.AgregadorDeCliente;
import com.bancoxyz.bff.web.panel.PanelClienteWeb;
import com.bancoxyz.bff.web.seguridad.UsuarioWeb;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Consola del ejecutivo. La cadena de seguridad ya exige ROLE_EJECUTIVO para /web/api/ejecutivo/**. */
@RestController
@RequestMapping("/web/api/ejecutivo")
public class ControladorEjecutivo {

    private final ClienteCoreWeb core;
    private final AdaptadorWeb adaptador;
    private final AgregadorDeCliente agregador;

    public ControladorEjecutivo(ClienteCoreWeb core, AdaptadorWeb adaptador, AgregadorDeCliente agregador) {
        this.core = core;
        this.adaptador = adaptador;
        this.agregador = agregador;
    }

    @Operation(summary = "Resumen diario de transacciones con tabla y serie para grafico")
    @GetMapping("/resumen-diario")
    public ResumenDiarioWeb resumenDiario(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                                          @AuthenticationPrincipal UsuarioWeb usuario) {
        if (desde != null && hasta != null && desde.isAfter(hasta)) {
            throw new ErrorWeb(HttpStatus.BAD_REQUEST, "SOLICITUD_INVALIDA", "Solicitud inválida", "desde es posterior a hasta");
        }
        return adaptador.resumenDiario(core.resumenDiario(usuario.usuarioId(), desde, hasta, FiltroCorrelacion.actual()));
    }

    @Operation(summary = "Calidad de los cierres publicados y filas rechazadas por el batch")
    @GetMapping("/calidad")
    public CalidadWeb calidad(@AuthenticationPrincipal UsuarioWeb usuario) {
        return adaptador.calidad(core.calidad(usuario.usuarioId(), FiltroCorrelacion.actual()));
    }

    @Operation(summary = "Panel de un cliente visto por el ejecutivo")
    @GetMapping("/clientes/{clienteId}/panel")
    public PanelClienteWeb panelDeCliente(@PathVariable long clienteId, @AuthenticationPrincipal UsuarioWeb usuario) {
        String correlacion = FiltroCorrelacion.actual();
        String titular = core.clientes(usuario.usuarioId(), correlacion).stream()
                .filter(c -> c.clienteId() == clienteId).map(ClienteDelBancoCore::nombre).findFirst()
                .orElseThrow(() -> new ErrorWeb(HttpStatus.NOT_FOUND, "CLIENTE_NO_ENCONTRADO", "Cliente no encontrado",
                        "No existe el cliente " + clienteId));
        return agregador.panel(usuario.usuarioId(), clienteId, titular, correlacion);
    }
}
