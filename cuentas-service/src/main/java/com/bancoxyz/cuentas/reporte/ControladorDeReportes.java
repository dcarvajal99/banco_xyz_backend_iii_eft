package com.bancoxyz.cuentas.reporte;

import java.time.LocalDate;
import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import com.bancoxyz.cuentas.seguridad.IdentidadDelToken;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Reportes del cierre para la consola del ejecutivo (reportes.leer + rol EJECUTIVO). */
@RestController
@RequestMapping("/api/v1/reportes")
public class ControladorDeReportes {

    private final LectorDeReportes lector;

    public ControladorDeReportes(LectorDeReportes lector) {
        this.lector = lector;
    }

    @Operation(summary = "Resumen diario de transacciones del cierre publicado")
    @GetMapping("/resumen-diario")
    public ResponseEntity<List<ResumenDiarioDto>> resumenDiario(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            Authentication autenticacion) {
        IdentidadDelToken.de(autenticacion).exigirEjecutivo();
        return ResponseEntity.ok(lector.resumenDiario(desde, hasta));
    }

    @Operation(summary = "Calidad de los cierres publicados y filas rechazadas")
    @GetMapping("/calidad")
    public ResponseEntity<CalidadDto> calidad(Authentication autenticacion) {
        IdentidadDelToken.de(autenticacion).exigirEjecutivo();
        return ResponseEntity.ok(lector.calidad());
    }

}
