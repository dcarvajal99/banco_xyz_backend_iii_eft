package com.bancoxyz.bff.movil.seguridad;

import com.bancoxyz.bff.movil.config.FiltroCorrelacion;
import com.bancoxyz.bff.movil.core.CoreApi;
import com.bancoxyz.bff.movil.core.UsuarioAutenticadoCore;
import com.bancoxyz.bff.movil.error.ErrorMovil;
import com.bancoxyz.bff.movil.seguridad.AlmacenDeRefresh.Renovacion;
import com.bancoxyz.bff.movil.seguridad.EmisorDeTokens.ParDeTokens;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/movil/v1/sesiones")
public class ControladorDeSesiones {

    public record SolicitudLogin(@NotBlank @Size(max = 60) String usuario, @NotBlank @Size(max = 200) String password,
                                 @NotBlank @Size(max = 100) String dispositivoId) {
    }

    public record SolicitudRenovacion(@NotBlank String refreshToken, @NotBlank @Size(max = 100) String dispositivoId) {
    }

    private final CoreApi core;
    private final EmisorDeTokens emisor;
    private final AlmacenDeRefresh refresh;

    public ControladorDeSesiones(CoreApi core, EmisorDeTokens emisor, AlmacenDeRefresh refresh) {
        this.core = core;
        this.emisor = emisor;
        this.refresh = refresh;
    }

    @Operation(summary = "Inicia sesion desde un dispositivo (no enviar Authorization)")
    @PostMapping
    public ResponseEntity<ParDeTokens> iniciar(@Valid @RequestBody SolicitudLogin solicitud) {
        // El core rechaza a los ejecutivos por este canal con 403 ROL_NO_PERMITIDO_EN_CANAL.
        UsuarioAutenticadoCore usuario = core.autenticar(solicitud.usuario(), solicitud.password(), FiltroCorrelacion.actual());
        if (usuario.clienteId() == null) {
            throw new ErrorMovil(HttpStatus.FORBIDDEN, "ROL_NO_PERMITIDO_EN_CANAL", "Este usuario no puede ingresar desde la app.");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(emisor.emitir(usuario.usuarioId(), usuario.clienteId(),
                usuario.nombre(), solicitud.dispositivoId(), EmisorDeTokens.nuevaFamilia()));
    }

    @Operation(summary = "Renueva el par de tokens: el refresh usado queda consumido")
    @PostMapping("/renovar")
    public ResponseEntity<ParDeTokens> renovar(@Valid @RequestBody SolicitudRenovacion solicitud) {
        Renovacion renovacion = refresh.consumir(solicitud.refreshToken(), solicitud.dispositivoId());
        return ResponseEntity.status(HttpStatus.CREATED).body(emisor.emitir(renovacion.usuarioId(), renovacion.clienteId(),
                renovacion.nombre(), renovacion.dispositivo(), renovacion.familia()));
    }

    @Operation(summary = "Cierra la sesion: revoca la familia de tokens")
    @DeleteMapping
    public ResponseEntity<Void> cerrar(@AuthenticationPrincipal Jwt jwt) {
        refresh.revocarFamilia(UsuarioMovil.desde(jwt).familia());
        return ResponseEntity.noContent().build();
    }
}
