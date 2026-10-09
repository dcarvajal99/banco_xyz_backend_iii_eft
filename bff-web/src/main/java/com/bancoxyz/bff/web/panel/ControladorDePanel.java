package com.bancoxyz.bff.web.panel;

import java.util.List;

import com.bancoxyz.bff.web.config.FiltroCorrelacion;
import com.bancoxyz.bff.web.error.ErrorWeb;
import com.bancoxyz.bff.web.seguridad.UsuarioWeb;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ControladorDePanel {

    private final List<EstrategiaDePanel> estrategias;

    public ControladorDePanel(List<EstrategiaDePanel> estrategias) {
        this.estrategias = estrategias;
    }

    @Operation(summary = "Panel de inicio: el del cliente o el del ejecutivo segun quien inicio sesion")
    @GetMapping("/web/api/panel")
    public Object panel(@AuthenticationPrincipal UsuarioWeb usuario) {
        // La identidad y la correlacion se toman aqui, en el hilo de la solicitud, y bajan como parametros.
        EstrategiaDePanel estrategia = estrategias.stream().filter(e -> e.atiende(usuario)).findFirst()
                .orElseThrow(() -> new ErrorWeb(HttpStatus.FORBIDDEN, "ACCESO_DENEGADO", "Acceso denegado",
                        "No hay un panel para este tipo de usuario"));
        return estrategia.construir(usuario, FiltroCorrelacion.actual());
    }
}
