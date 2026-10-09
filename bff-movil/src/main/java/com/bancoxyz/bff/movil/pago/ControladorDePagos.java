package com.bancoxyz.bff.movil.pago;

import java.math.BigDecimal;

import com.bancoxyz.bff.movil.config.FiltroCorrelacion;
import com.bancoxyz.bff.movil.core.CoreApi;
import com.bancoxyz.bff.movil.core.PagoCore;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Transferir desde la app y seguir su estado. Respuestas minimas: la app solo muestra el estado. */
@RestController
@Validated
public class ControladorDePagos {

    /** Lo que la app envia: tres campos, sin nombres largos. */
    public record SolicitudTransferencia(@NotNull @Positive Long origen, @NotNull @Positive Long destino,
                                         @NotNull @Positive BigDecimal monto) {
    }

    /** Lo que la app recibe: id, estado y motivo si fue rechazada. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EstadoMovil(String id, String estado, String motivo) {

        static EstadoMovil de(PagoCore pago) {
            return new EstadoMovil(pago.id(), pago.estado(), pago.motivo());
        }
    }

    private final CoreApi core;

    public ControladorDePagos(CoreApi core) {
        this.core = core;
    }

    @Operation(summary = "Transferencia entre cuentas propias o a terceros (pagos.escribir)")
    @PostMapping("/movil/v1/transferencias")
    public ResponseEntity<EstadoMovil> transferir(@RequestHeader("Idempotency-Key") @NotBlank @Size(max = 80) String clave,
                                                  @Valid @RequestBody SolicitudTransferencia s) {
        PagoCore pago = core.transferir(s.origen(), s.destino(), s.monto(), clave, FiltroCorrelacion.actual());
        return ResponseEntity.accepted().body(EstadoMovil.de(pago));
    }

    @Operation(summary = "Estado de una operacion (pagos.leer)")
    @GetMapping("/movil/v1/pagos/{id}")
    public ResponseEntity<EstadoMovil> pago(@PathVariable String id) {
        return ResponseEntity.ok(EstadoMovil.de(core.pago(id, FiltroCorrelacion.actual())));
    }
}
