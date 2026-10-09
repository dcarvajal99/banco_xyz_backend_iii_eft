package com.bancoxyz.bff.web.seguridad;

import java.util.List;
import java.util.Map;

import com.bancoxyz.bff.web.config.FiltroCorrelacion;
import com.bancoxyz.bff.web.core.ClienteCoreWeb;
import com.bancoxyz.bff.web.core.UsuarioAutenticadoCore;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/web/api")
public class ControladorDeSesion {

    public record SolicitudLogin(@NotBlank @Size(max = 60) String usuario, @NotBlank @Size(max = 200) String password) {
    }

    public record SesionWeb(String nombre, String rol, int expiraEnSegundos) {
    }

    private final ClienteCoreWeb core;
    private final SecurityContextRepository repositorio;
    private final SecurityContextHolderStrategy estrategia = SecurityContextHolder.getContextHolderStrategy();

    public ControladorDeSesion(ClienteCoreWeb core, SecurityContextRepository repositorio) {
        this.core = core;
        this.repositorio = repositorio;
    }

    @Operation(summary = "Inicia sesion (solo application/json). Responde con la cookie HttpOnly BFFWEB_SESION")
    @PostMapping(value = "/sesion", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SesionWeb> iniciar(@Valid @RequestBody SolicitudLogin solicitud, HttpServletRequest request,
                                             HttpServletResponse response) {
        UsuarioAutenticadoCore autenticado = core.autenticar(solicitud.usuario(), solicitud.password(), FiltroCorrelacion.actual());
        UsuarioWeb usuario = new UsuarioWeb(autenticado.usuarioId(), autenticado.clienteId(), autenticado.nombre(),
                autenticado.rol());

        // Fijacion de sesion: si el navegador traia una sesion (quiza plantada por un atacante), se descarta y la
        // identidad queda en una sesion con id nuevo.
        HttpSession previa = request.getSession(false);
        if (previa != null) {
            previa.invalidate();
        }
        HttpSession sesion = request.getSession(true);

        SecurityContext contexto = estrategia.createEmptyContext();
        contexto.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(usuario, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + usuario.rol()))));
        estrategia.setContext(contexto);
        // Spring Security 6 no guarda solo el contexto en un login manual: sin esto la siguiente solicitud seria 401.
        repositorio.saveContext(contexto, request, response);
        return ResponseEntity.ok(new SesionWeb(usuario.nombre(), usuario.rol(), sesion.getMaxInactiveInterval()));
    }

    @Operation(summary = "Usuario de la sesion actual")
    @GetMapping("/sesion")
    public SesionWeb actual(@AuthenticationPrincipal UsuarioWeb usuario, HttpSession sesion) {
        return new SesionWeb(usuario.nombre(), usuario.rol(), sesion.getMaxInactiveInterval());
    }

    @Operation(summary = "Cierra la sesion (exige el token CSRF)")
    @DeleteMapping("/sesion")
    public ResponseEntity<Void> cerrar(HttpServletRequest request) {
        HttpSession sesion = request.getSession(false);
        if (sesion != null) {
            sesion.invalidate();
        }
        estrategia.clearContext();
        return ResponseEntity.noContent().build();
    }

    /** El token CSRF es diferido en Spring Security 6: leerlo aqui obliga a escribir la cookie XSRF-TOKEN. */
    @Operation(summary = "Entrega el token CSRF y fuerza la cookie XSRF-TOKEN")
    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("encabezado", token.getHeaderName(), "token", token.getToken());
    }
}
