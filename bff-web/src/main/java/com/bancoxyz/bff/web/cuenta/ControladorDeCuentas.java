package com.bancoxyz.bff.web.cuenta;

import com.bancoxyz.bff.web.adaptador.AdaptadorWeb;
import com.bancoxyz.bff.web.adaptador.PaginaWeb;
import com.bancoxyz.bff.web.config.FiltroCorrelacion;
import com.bancoxyz.bff.web.core.ClienteCoreWeb;
import com.bancoxyz.bff.web.error.ErrorWeb;
import com.bancoxyz.bff.web.seguridad.UsuarioWeb;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Detalle de una cuenta. Si es ajena, el core responde 404 con el usuario de la sesion: el BFF no decide eso solo. */
@RestController
@RequestMapping("/web/api/cuentas")
public class ControladorDeCuentas {

    static final int TAMANO_POR_DEFECTO = 25;
    static final int TAMANO_MAXIMO = 100;

    private final ClienteCoreWeb core;
    private final AdaptadorWeb adaptador;

    public ControladorDeCuentas(ClienteCoreWeb core, AdaptadorWeb adaptador) {
        this.core = core;
        this.adaptador = adaptador;
    }

    @Operation(summary = "Cuenta completa con estados anuales y la primera pagina de movimientos")
    @GetMapping("/{cuentaId}")
    public DetalleCuentaWeb detalle(@PathVariable long cuentaId, @AuthenticationPrincipal UsuarioWeb usuario) {
        String correlacion = FiltroCorrelacion.actual();
        return new DetalleCuentaWeb(
                adaptador.cuenta(core.cuenta(cuentaId, usuario.usuarioId(), correlacion)),
                core.estadosAnuales(cuentaId, usuario.usuarioId(), correlacion).stream().map(adaptador::estado).toList(),
                adaptador.pagina(core.movimientos(cuentaId, usuario.usuarioId(), 0, TAMANO_POR_DEFECTO, correlacion)));
    }

    @Operation(summary = "Movimientos paginados con metadatos para una tabla")
    @GetMapping("/{cuentaId}/movimientos")
    public PaginaWeb movimientos(@PathVariable long cuentaId, @RequestParam(defaultValue = "0") int pagina,
                                 @RequestParam(defaultValue = "25") int tamano, @AuthenticationPrincipal UsuarioWeb usuario) {
        if (pagina < 0 || tamano < 1 || tamano > TAMANO_MAXIMO) {
            throw new ErrorWeb(HttpStatus.BAD_REQUEST, "SOLICITUD_INVALIDA", "Solicitud inválida",
                    "pagina debe ser >= 0 y tamano entre 1 y " + TAMANO_MAXIMO);
        }
        return adaptador.pagina(core.movimientos(cuentaId, usuario.usuarioId(), pagina, tamano, FiltroCorrelacion.actual()));
    }
}
