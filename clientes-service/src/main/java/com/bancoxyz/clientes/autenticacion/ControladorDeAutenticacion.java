package com.bancoxyz.clientes.autenticacion;

import com.bancoxyz.clientes.autenticacion.ServicioDeAutenticacion.ResultadoIdentificacion;
import com.bancoxyz.clientes.autenticacion.ServicioDeAutenticacion.ResultadoUsuario;
import com.bancoxyz.clientes.cliente.RepositorioClientes;
import com.bancoxyz.clientes.error.Errores;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Verificacion de credenciales para banco-auth (clave de servicio + certificado). Ningun BFF llega aqui. */
@RestController
@RequestMapping("/api/v1/autenticacion")
@SecurityRequirement(name = "servicioDeAutenticacion")
public class ControladorDeAutenticacion {

    private final ServicioDeAutenticacion servicio;
    private final RepositorioClientes clientes;

    public ControladorDeAutenticacion(ServicioDeAutenticacion servicio, RepositorioClientes clientes) {
        this.servicio = servicio;
        this.clientes = clientes;
    }

    @Operation(summary = "Verifica usuario y clave (formulario de inicio de sesion de banco-auth)")
    @PostMapping("/usuarios")
    public ResponseEntity<UsuarioAutenticadoDto> usuario(@Valid @RequestBody SolicitudUsuario solicitud) {
        // El servicio ya confirmo su transaccion: el intento fallido queda contado aunque aqui se lance el error.
        ResultadoUsuario resultado = servicio.autenticarUsuario(solicitud.usuario(), solicitud.password());
        return switch (resultado.estado()) {
            case AUTENTICADO -> ResponseEntity.ok(dto(resultado.usuario()));
            case CREDENCIALES_INVALIDAS -> throw Errores.credencialesInvalidas();
            case BLOQUEADO -> throw Errores.usuarioBloqueado();
        };
    }

    @Operation(summary = "Identifica a un cliente sin clave (inicio de sesion con GitHub en banco-auth)")
    @GetMapping("/usuarios/{usuario}")
    public ResponseEntity<UsuarioAutenticadoDto> identificar(@PathVariable String usuario) {
        ResultadoIdentificacion resultado = servicio.identificarUsuario(usuario);
        return switch (resultado.estado()) {
            case IDENTIFICADO -> ResponseEntity.ok(dto(resultado.usuario()));
            case NO_ENCONTRADO -> throw Errores.usuarioNoEncontrado();
            case BLOQUEADO -> throw Errores.usuarioBloqueado();
            case ROL_NO_PERMITIDO -> throw Errores.rolNoPermitido();
        };
    }

    private UsuarioAutenticadoDto dto(Usuario u) {
        String nombre = u.getClienteId() == null ? "Ejecutivo del banco"
                : clientes.findById(u.getClienteId()).map(c -> c.getNombre()).orElse(u.getUsuario());
        return new UsuarioAutenticadoDto(u.getId(), u.getClienteId(), u.getUsuario(), nombre, u.getRol());
    }
}
