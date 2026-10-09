package com.bancoxyz.cuentas.autenticacion;

import com.bancoxyz.cuentas.autenticacion.ServicioDeAutenticacion.ResultadoTarjeta;
import com.bancoxyz.cuentas.cuenta.RepositorioCuentas;
import com.bancoxyz.cuentas.error.Errores;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tarjeta + PIN para el BFF del cajero (scope cajero.operar). */
@RestController
@RequestMapping("/api/v1/autenticacion")
public class ControladorDeAutenticacion {

    private final ServicioDeAutenticacion servicio;
    private final RepositorioCuentas cuentas;

    public ControladorDeAutenticacion(ServicioDeAutenticacion servicio, RepositorioCuentas cuentas) {
        this.servicio = servicio;
        this.cuentas = cuentas;
    }

    @Operation(summary = "Verifica tarjeta y PIN (cajero.operar)")
    @PostMapping("/tarjetas")
    public ResponseEntity<TarjetaAutenticadaDto> tarjeta(@Valid @RequestBody SolicitudTarjeta solicitud) {
        ResultadoTarjeta resultado = servicio.autenticarTarjeta(solicitud.numeroTarjeta(), solicitud.pin());
        return switch (resultado.estado()) {
            case AUTENTICADA -> {
                var tarjeta = resultado.tarjeta();
                String titular = cuentas.findById(tarjeta.getCuentaId()).map(c -> c.getTitular()).orElse("");
                yield ResponseEntity.ok(new TarjetaAutenticadaDto(tarjeta.getId(), tarjeta.getCuentaId(),
                        tarjeta.getUltimos4(), titular));
            }
            case TARJETA_INVALIDA -> throw Errores.tarjetaInvalida();
            case PIN_INCORRECTO -> throw Errores.pinIncorrecto(resultado.intentosRestantes());
            case BLOQUEADA -> throw Errores.tarjetaBloqueada();
        };
    }
}
