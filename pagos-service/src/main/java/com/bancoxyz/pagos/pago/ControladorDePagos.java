package com.bancoxyz.pagos.pago;

import java.net.URI;
import java.util.List;

import com.bancoxyz.pagos.seguridad.FiltroCertificadoDeLlamante;
import com.bancoxyz.pagos.seguridad.IdentidadDelToken;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 202 Accepted: el pago quedo registrado y se procesa por Kafka; el estado final se consulta en Location. Un reintento
 * con la misma Idempotency-Key devuelve el mismo pago (200 + Idempotency-Replayed).
 */
@RestController
@Validated
@RequestMapping("/api/v1/pagos")
public class ControladorDePagos {

    private final ServicioDePagos servicio;

    public ControladorDePagos(ServicioDePagos servicio) {
        this.servicio = servicio;
    }

    @Operation(summary = "Transferencia entre cuentas (pagos.escribir)")
    @PostMapping("/transferencias")
    public ResponseEntity<PagoDto> transferir(@RequestHeader("Idempotency-Key") @NotBlank @Size(max = 80) String clave,
                                              @Valid @RequestBody Solicitudes.Transferencia s,
                                              Authentication autenticacion, HttpServletRequest request) {
        return responder(servicio.solicitar(IdentidadDelToken.de(autenticacion), new ServicioDePagos.Solicitud(
                Pago.TRANSFERENCIA, s.cuentaOrigen(), s.cuentaDestino(), null, s.monto()), clave, canal(request)));
    }

    @Operation(summary = "Deposito en una cuenta propia (pagos.escribir)")
    @PostMapping("/depositos")
    public ResponseEntity<PagoDto> depositar(@RequestHeader("Idempotency-Key") @NotBlank @Size(max = 80) String clave,
                                             @Valid @RequestBody Solicitudes.Deposito s,
                                             Authentication autenticacion, HttpServletRequest request) {
        return responder(servicio.solicitar(IdentidadDelToken.de(autenticacion), new ServicioDePagos.Solicitud(
                Pago.DEPOSITO, null, s.cuentaDestino(), null, s.monto()), clave, canal(request)));
    }

    @Operation(summary = "Pago de un servicio (luz, agua, telefonia) (pagos.escribir)")
    @PostMapping("/servicios")
    public ResponseEntity<PagoDto> pagarServicio(@RequestHeader("Idempotency-Key") @NotBlank @Size(max = 80) String clave,
                                                 @Valid @RequestBody Solicitudes.PagoServicio s,
                                                 Authentication autenticacion, HttpServletRequest request) {
        return responder(servicio.solicitar(IdentidadDelToken.de(autenticacion), new ServicioDePagos.Solicitud(
                Pago.PAGO_SERVICIO, s.cuentaOrigen(), null, s.convenio(), s.monto()), clave, canal(request)));
    }

    @Operation(summary = "Un pago propio con su historial (pagos.leer)")
    @GetMapping("/{id}")
    public ResponseEntity<PagoDto> consultar(@PathVariable String id, Authentication autenticacion) {
        return ResponseEntity.ok(servicio.consultar(IdentidadDelToken.de(autenticacion), id));
    }

    @Operation(summary = "Los ultimos 20 pagos propios (pagos.leer)")
    @GetMapping
    public ResponseEntity<List<PagoDto>> recientes(Authentication autenticacion) {
        return ResponseEntity.ok(servicio.recientes(IdentidadDelToken.de(autenticacion)));
    }

    private static ResponseEntity<PagoDto> responder(ServicioDePagos.Resultado resultado) {
        URI ubicacion = URI.create("/api/v1/pagos/" + resultado.pago().id());
        if (resultado.repetido()) {
            return ResponseEntity.ok().location(ubicacion).header("Idempotency-Replayed", "true").body(resultado.pago());
        }
        return ResponseEntity.accepted().location(ubicacion).body(resultado.pago());
    }

    /** Canal de origen segun el BFF que reenvio el token (su certificado). */
    private static String canal(HttpServletRequest request) {
        return FiltroCertificadoDeLlamante.nombreComun(request).filter("bff-movil"::equals).map(c -> "MOVIL").orElse("WEB");
    }
}
