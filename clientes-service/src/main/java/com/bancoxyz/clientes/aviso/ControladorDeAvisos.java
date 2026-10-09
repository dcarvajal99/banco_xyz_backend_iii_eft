package com.bancoxyz.clientes.aviso;

import java.util.List;

import com.bancoxyz.clientes.seguridad.IdentidadDelToken;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ControladorDeAvisos {

    private final ServicioDeAvisos servicio;

    public ControladorDeAvisos(ServicioDeAvisos servicio) {
        this.servicio = servicio;
    }

    @Operation(summary = "Ultimos 30 avisos del cliente: transacciones y alertas de seguridad (clientes.leer)")
    @GetMapping("/api/v1/clientes/{clienteId}/avisos")
    public ResponseEntity<List<AvisoDto>> avisos(@PathVariable long clienteId, Authentication autenticacion) {
        return ResponseEntity.ok(servicio.deCliente(IdentidadDelToken.de(autenticacion), clienteId));
    }
}
