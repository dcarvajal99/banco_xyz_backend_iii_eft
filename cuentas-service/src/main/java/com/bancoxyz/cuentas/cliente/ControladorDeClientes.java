package com.bancoxyz.cuentas.cliente;

import java.util.List;

import com.bancoxyz.cuentas.autenticacion.RepositorioUsuarios;
import com.bancoxyz.cuentas.autenticacion.Usuario;
import com.bancoxyz.cuentas.cuenta.RepositorioCuentas;
import com.bancoxyz.cuentas.seguridad.Canal;
import com.bancoxyz.cuentas.seguridad.ControlDeAcceso;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/clientes")
public class ControladorDeClientes {

    private final ControlDeAcceso acceso;
    private final RepositorioClientes clientes;
    private final RepositorioCuentas cuentas;
    private final RepositorioUsuarios usuarios;

    public ControladorDeClientes(ControlDeAcceso acceso, RepositorioClientes clientes, RepositorioCuentas cuentas,
                                 RepositorioUsuarios usuarios) {
        this.acceso = acceso;
        this.clientes = clientes;
        this.cuentas = cuentas;
        this.usuarios = usuarios;
    }

    @Operation(summary = "Clientes del banco (solo ejecutivos, por el canal web)")
    @GetMapping
    @Transactional(readOnly = true)
    public List<ClienteDto> listar(@RequestHeader(value = ControlDeAcceso.ENCABEZADO_USUARIO, required = false) String usuarioId,
                                   Authentication autenticacion) {
        Usuario usuario = acceso.usuario(Canal.desde(autenticacion), usuarioId);
        acceso.exigirEjecutivo(usuario);
        return clientes.findAllByOrderByNombreAsc().stream()
                .map(c -> new ClienteDto(c.getId(), c.getNombre(),
                        usuarios.findFirstByClienteId(c.getId()).map(Usuario::getUsuario).orElse(null),
                        cuentas.countByClienteId(c.getId())))
                .toList();
    }
}
