package com.bancoxyz.bff.web.api;

import com.bancoxyz.bff.web.seguridad.UsuarioWeb;
import com.bancoxyz.bff.web.servicios.ClienteServicios;
import com.bancoxyz.bff.web.servicios.Servicio;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Consola del ejecutivo: solo existe en el canal web. El BFF corta antes si la sesion no es de un ejecutivo, y cada
 * microservicio vuelve a exigir el rol y el scope del token (defensa en profundidad).
 */
@RestController
@RequestMapping("/web/api/ejecutivo")
public class ControladorDelEjecutivo {

    private final ClienteServicios servicios;

    public ControladorDelEjecutivo(ClienteServicios servicios) {
        this.servicios = servicios;
    }

    @GetMapping("/clientes")
    public ResponseEntity<JsonNode> clientes(Authentication a) {
        UsuarioWeb.de(a).exigirEjecutivo();
        return ResponseEntity.ok(servicios.get(Servicio.CLIENTES, "/clientes"));
    }

    @Operation(summary = "Alta de cliente con sus credenciales iniciales")
    @PostMapping("/clientes")
    public ResponseEntity<JsonNode> altaCliente(@RequestBody JsonNode cuerpo, Authentication a) {
        UsuarioWeb.de(a).exigirEjecutivo();
        return ControladorDelCliente.reenviar(servicios.enviar(HttpMethod.POST, Servicio.CLIENTES, "/clientes", cuerpo, null));
    }

    @GetMapping("/clientes/{id}")
    public ResponseEntity<JsonNode> ficha(@PathVariable long id, Authentication a) {
        UsuarioWeb.de(a).exigirEjecutivo();
        return ResponseEntity.ok(servicios.get(Servicio.CLIENTES, "/clientes/{id}", id));
    }

    @GetMapping("/clientes/{id}/cuentas")
    public ResponseEntity<JsonNode> cuentasDeCliente(@PathVariable long id, Authentication a) {
        UsuarioWeb.de(a).exigirEjecutivo();
        return ResponseEntity.ok(servicios.get(Servicio.CUENTAS, "/clientes/{id}/cuentas", id));
    }

    @Operation(summary = "Apertura de una cuenta para un cliente")
    @PostMapping("/cuentas")
    public ResponseEntity<JsonNode> abrir(@RequestBody JsonNode cuerpo, Authentication a) {
        UsuarioWeb.de(a).exigirEjecutivo();
        return ControladorDelCliente.reenviar(servicios.enviar(HttpMethod.POST, Servicio.CUENTAS, "/cuentas", cuerpo, null));
    }

    @Operation(summary = "Mantenimiento: alias y bloqueo/desbloqueo")
    @PatchMapping("/cuentas/{id}")
    public ResponseEntity<JsonNode> mantener(@PathVariable long id, @RequestBody JsonNode cuerpo, Authentication a) {
        UsuarioWeb.de(a).exigirEjecutivo();
        return ControladorDelCliente.reenviar(servicios.enviar(HttpMethod.PATCH, Servicio.CUENTAS, "/cuentas/{id}", cuerpo, null, id));
    }

    @Operation(summary = "Cierre de una cuenta con saldo cero")
    @PostMapping("/cuentas/{id}/cierre")
    public ResponseEntity<JsonNode> cerrar(@PathVariable long id, @RequestBody JsonNode cuerpo, Authentication a) {
        UsuarioWeb.de(a).exigirEjecutivo();
        return ControladorDelCliente.reenviar(servicios.enviar(HttpMethod.POST, Servicio.CUENTAS, "/cuentas/{id}/cierre", cuerpo, null, id));
    }

    @Operation(summary = "Calidad de la migracion: cierres publicados y filas rechazadas por archivo")
    @GetMapping("/reportes/calidad")
    public ResponseEntity<JsonNode> calidad(Authentication a) {
        UsuarioWeb.de(a).exigirEjecutivo();
        return ResponseEntity.ok(servicios.get(Servicio.CUENTAS, "/reportes/calidad"));
    }

    @Operation(summary = "Reporte de transacciones diarias del cierre publicado")
    @GetMapping("/reportes/resumen-diario")
    public ResponseEntity<JsonNode> resumenDiario(@RequestParam(required = false) String desde,
                                                  @RequestParam(required = false) String hasta, Authentication a) {
        UsuarioWeb.de(a).exigirEjecutivo();
        String ruta = "/reportes/resumen-diario" + (desde == null ? "" : "?desde=" + desde)
                + (hasta == null ? "" : (desde == null ? "?" : "&") + "hasta=" + hasta);
        return ResponseEntity.ok(servicios.get(Servicio.CUENTAS, ruta));
    }
}
