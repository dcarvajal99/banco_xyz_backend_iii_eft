package com.bancoxyz.clientes.cliente;

import java.net.URI;
import java.util.List;

import com.bancoxyz.clientes.seguridad.IdentidadDelToken;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/clientes")
public class ControladorDeClientes {

    private final ServicioDeClientes servicio;

    public ControladorDeClientes(ServicioDeClientes servicio) {
        this.servicio = servicio;
    }

    @Operation(summary = "Clientes del banco (solo ejecutivos; scope clientes.leer)")
    @GetMapping
    public ResponseEntity<List<ClienteResumenDto>> listar(Authentication autenticacion) {
        return ResponseEntity.ok(servicio.listar(IdentidadDelToken.de(autenticacion)));
    }

    @Operation(summary = "Ficha de un cliente: el propio cliente o un ejecutivo (scope clientes.leer)")
    @GetMapping("/{clienteId}")
    public ResponseEntity<ClienteDto> consultar(@PathVariable long clienteId, Authentication autenticacion) {
        return ResponseEntity.ok(servicio.consultar(IdentidadDelToken.de(autenticacion), clienteId));
    }

    @Operation(summary = "Actualiza correo, telefono y direccion (scope clientes.perfil)")
    @PutMapping("/{clienteId}/perfil")
    public ResponseEntity<ClienteDto> actualizarPerfil(@PathVariable long clienteId,
                                                       @Valid @RequestBody SolicitudPerfil solicitud,
                                                       Authentication autenticacion) {
        return ResponseEntity.ok(servicio.actualizarPerfil(IdentidadDelToken.de(autenticacion), clienteId, solicitud));
    }

    @Operation(summary = "Alta de un cliente con sus credenciales iniciales (ejecutivo; scope clientes.administrar)")
    @PostMapping
    public ResponseEntity<ClienteDto> alta(@Valid @RequestBody SolicitudAltaCliente solicitud, Authentication autenticacion) {
        ClienteDto creado = servicio.alta(IdentidadDelToken.de(autenticacion), solicitud);
        return ResponseEntity.created(URI.create("/api/v1/clientes/" + creado.clienteId())).body(creado);
    }
}
