package com.bancoxyz.bff.web.api;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.bancoxyz.bff.web.error.ServicioNoDisponible;
import com.bancoxyz.bff.web.seguridad.UsuarioWeb;
import com.bancoxyz.bff.web.servicios.ClienteServicios;
import com.bancoxyz.bff.web.servicios.Servicio;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** API del navegador para el cliente: panel agregado, movimientos, pagos y perfil. */
@RestController
@RequestMapping("/web/api")
public class ControladorDelCliente {

    private final ClienteServicios servicios;
    private final ObjectMapper json;

    public ControladorDelCliente(ClienteServicios servicios, ObjectMapper json) {
        this.servicios = servicios;
        this.json = json;
    }

    @Operation(summary = "Quien tiene la sesion (publica: sin sesion responde autenticado=false)")
    @GetMapping("/sesion")
    public ResponseEntity<Map<String, Object>> sesion(Authentication autenticacion, HttpServletRequest request) {
        Map<String, Object> sesion = new LinkedHashMap<>();
        if (request.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken csrf) {
            csrf.getToken();
        }
        if (autenticacion == null || !(autenticacion.getPrincipal() instanceof org.springframework.security.oauth2.core.oidc.user.OidcUser)) {
            sesion.put("autenticado", false);
            return ResponseEntity.ok(sesion);
        }
        UsuarioWeb usuario = UsuarioWeb.de(autenticacion);
        sesion.put("autenticado", true);
        sesion.put("usuario", usuario.usuario());
        sesion.put("rol", usuario.rol());
        sesion.put("clienteId", usuario.clienteId());
        sesion.put("origen", usuario.origen());
        return ResponseEntity.ok(sesion);
    }

    @Operation(summary = "Panel del cliente: ficha, cuentas, totales, pagos recientes y avisos en una respuesta")
    @GetMapping("/panel")
    public ResponseEntity<PanelWeb> panel(Authentication autenticacion) {
        UsuarioWeb usuario = UsuarioWeb.de(autenticacion);
        long clienteId = usuario.clienteId();
        // Las cuentas son el corazon del panel: si cuentas-service no responde, el error sube (503).
        JsonNode cuentas = servicios.get(Servicio.CUENTAS, "/clientes/{id}/cuentas", clienteId);
        List<String> noDisponibles = new ArrayList<>();
        JsonNode cliente = opcional(() -> servicios.get(Servicio.CLIENTES, "/clientes/{id}", clienteId), "cliente", noDisponibles);
        JsonNode pagos = opcional(() -> servicios.get(Servicio.PAGOS, "/pagos"), "pagos", noDisponibles);
        JsonNode avisos = opcional(() -> servicios.get(Servicio.CLIENTES, "/clientes/{id}/avisos", clienteId), "avisos", noDisponibles);
        BigDecimal disponible = BigDecimal.ZERO;
        BigDecimal deuda = BigDecimal.ZERO;
        for (JsonNode cuenta : cuentas) {
            BigDecimal saldo = cuenta.path("saldoDisponible").decimalValue();
            if ("ahorro".equals(cuenta.path("tipo").asText())) {
                disponible = disponible.add(saldo);
            } else {
                deuda = deuda.add(saldo);
            }
        }
        return ResponseEntity.ok(new PanelWeb(usuario.usuario(), cliente, cuentas, disponible, deuda, pagos, avisos, noDisponibles));
    }

    @GetMapping("/cuentas/{id}/movimientos")
    public ResponseEntity<JsonNode> movimientos(@PathVariable long id, @RequestParam(defaultValue = "0") int pagina,
                                                @RequestParam(defaultValue = "15") int tamano) {
        return ResponseEntity.ok(servicios.get(Servicio.CUENTAS, "/cuentas/{id}/movimientos?pagina={p}&tamano={t}", id, pagina, tamano));
    }

    @GetMapping("/cuentas/{id}/estados-anuales")
    public ResponseEntity<JsonNode> estadosAnuales(@PathVariable long id) {
        return ResponseEntity.ok(servicios.get(Servicio.CUENTAS, "/cuentas/{id}/estados-anuales", id));
    }

    @Operation(summary = "Transferencia, deposito o pago de servicio (tipo = transferencias | depositos | servicios)")
    @PostMapping("/pagos/{tipo}")
    public ResponseEntity<JsonNode> pagar(@PathVariable String tipo, @RequestHeader("Idempotency-Key") String clave,
                                          @RequestBody JsonNode cuerpo) {
        if (!List.of("transferencias", "depositos", "servicios").contains(tipo)) {
            return ResponseEntity.notFound().build();
        }
        return reenviar(servicios.enviar(HttpMethod.POST, Servicio.PAGOS, "/pagos/" + tipo, cuerpo, clave));
    }

    @GetMapping("/pagos/{id}")
    public ResponseEntity<JsonNode> pago(@PathVariable String id) {
        return ResponseEntity.ok(servicios.get(Servicio.PAGOS, "/pagos/{id}", id));
    }

    @Operation(summary = "Actualiza correo, telefono y direccion del propio cliente")
    @PutMapping("/perfil")
    public ResponseEntity<JsonNode> perfil(@RequestBody JsonNode cuerpo, Authentication autenticacion) {
        long clienteId = UsuarioWeb.de(autenticacion).clienteId();
        return reenviar(servicios.enviar(HttpMethod.PUT, Servicio.CLIENTES, "/clientes/{id}/perfil", cuerpo, null, clienteId));
    }

    private JsonNode opcional(Supplier<JsonNode> llamada, String seccion, List<String> noDisponibles) {
        try {
            return llamada.get();
        } catch (ServicioNoDisponible caido) {
            noDisponibles.add(seccion);
            return json.nullNode();
        }
    }

    static ResponseEntity<JsonNode> reenviar(ResponseEntity<JsonNode> respuesta) {
        return ResponseEntity.status(respuesta.getStatusCode()).body(respuesta.getBody());
    }
}
