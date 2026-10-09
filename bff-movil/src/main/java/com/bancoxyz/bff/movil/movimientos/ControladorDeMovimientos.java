package com.bancoxyz.bff.movil.movimientos;

import com.bancoxyz.bff.movil.adaptador.AdaptadorMovil;
import com.bancoxyz.bff.movil.config.FiltroCorrelacion;
import com.bancoxyz.bff.movil.core.CoreApi;
import com.bancoxyz.bff.movil.core.PaginaMovimientosCore;
import com.bancoxyz.bff.movil.error.ErrorMovil;
import com.bancoxyz.bff.movil.inicio.ControladorDeInicio;
import com.bancoxyz.bff.movil.seguridad.UsuarioMovil;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ControladorDeMovimientos {

    static final int LIMITE_MAXIMO = 20;

    private final CoreApi core;
    private final AdaptadorMovil adaptador;

    public ControladorDeMovimientos(CoreApi core, AdaptadorMovil adaptador) {
        this.core = core;
        this.adaptador = adaptador;
    }

    @Operation(summary = "Movimientos para 'cargar mas', paginados por cursor")
    @GetMapping("/movil/v1/cuentas/{cuentaId}/movimientos")
    public ResponseEntity<PaginaMovil> movimientos(@PathVariable long cuentaId, @RequestParam(required = false) String cursor,
                                   @RequestParam(defaultValue = "10") int limite, @AuthenticationPrincipal Jwt jwt) {
        if (limite < 1 || limite > LIMITE_MAXIMO) {
            throw new ErrorMovil(HttpStatus.BAD_REQUEST, "SOLICITUD_INVALIDA", "El límite debe estar entre 1 y " + LIMITE_MAXIMO + ".");
        }
        Cursor actual = cursor == null || cursor.isBlank() ? new Cursor(0, limite) : Cursor.decodificar(cursor);
        UsuarioMovil usuario = UsuarioMovil.desde(jwt);
        // La propiedad de la cuenta la decide el core con el usuario del token: cuenta ajena -> 404.
        PaginaMovimientosCore pagina = core.movimientos(cuentaId, usuario.usuarioId(), actual.pagina(), actual.limite(),
                FiltroCorrelacion.actual());
        String siguiente = actual.pagina() + 1 < pagina.totalPaginas() ? new Cursor(actual.pagina() + 1, actual.limite()).codificar() : null;
        return ResponseEntity.ok().cacheControl(ControladorDeInicio.REVALIDAR_EN_LA_APP)
                .body(new PaginaMovil(pagina.contenido().stream().map(adaptador::movimiento).toList(), siguiente));
    }
}
