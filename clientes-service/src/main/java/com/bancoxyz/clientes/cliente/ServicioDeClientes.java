package com.bancoxyz.clientes.cliente;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.bancoxyz.clientes.autenticacion.CodificadorDeSecretos;
import com.bancoxyz.clientes.autenticacion.RepositorioUsuarios;
import com.bancoxyz.clientes.autenticacion.Usuario;
import com.bancoxyz.clientes.error.Errores;
import com.bancoxyz.clientes.seguridad.IdentidadDelToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Consultas y cambios de la ficha de un cliente, siempre con la identidad del token. */
@Service
public class ServicioDeClientes {

    private static final Logger log = LoggerFactory.getLogger(ServicioDeClientes.class);

    private final RepositorioClientes clientes;
    private final RepositorioUsuarios usuarios;
    private final CodificadorDeSecretos codificador;
    private final JdbcClient jdbc;
    private final Clock reloj;

    public ServicioDeClientes(RepositorioClientes clientes, RepositorioUsuarios usuarios, CodificadorDeSecretos codificador,
                              JdbcClient jdbc, Clock reloj) {
        this.clientes = clientes;
        this.usuarios = usuarios;
        this.codificador = codificador;
        this.jdbc = jdbc;
        this.reloj = reloj;
    }

    @Transactional(readOnly = true)
    public List<ClienteResumenDto> listar(IdentidadDelToken quien) {
        quien.exigirEjecutivo();
        Map<Long, String> usuarioPorCliente = usuarios.findAll().stream().filter(u -> u.getClienteId() != null)
                .collect(Collectors.toMap(Usuario::getClienteId, Usuario::getUsuario, (a, b) -> a));
        return clientes.findAllByOrderByNombreAsc().stream()
                .map(c -> new ClienteResumenDto(c.getId(), c.getNombre(), usuarioPorCliente.get(c.getId()), c.getEmail(),
                        c.getOrigen()))
                .toList();
    }

    @Transactional(readOnly = true)
    public ClienteDto consultar(IdentidadDelToken quien, long clienteId) {
        quien.exigirClienteVisible(clienteId);
        return dto(clientes.findById(clienteId).orElseThrow(Errores::clienteNoEncontrado));
    }

    @Transactional
    public ClienteDto actualizarPerfil(IdentidadDelToken quien, long clienteId, SolicitudPerfil solicitud) {
        quien.exigirClienteVisible(clienteId);
        Cliente cliente = clientes.findById(clienteId).orElseThrow(Errores::clienteNoEncontrado);
        cliente.actualizarPerfil(solicitud.comoPerfil(), LocalDateTime.now(reloj));
        // saveAndFlush: la version se incrementa aqui y un conflicto sale como 409 antes de responder.
        log.info("Perfil del cliente {} actualizado por {} ({})", clienteId, quien.usuario(), quien.rol());
        return dto(clientes.saveAndFlush(cliente));
    }

    @Transactional
    public ClienteDto alta(IdentidadDelToken quien, SolicitudAltaCliente solicitud) {
        quien.exigirEjecutivo();
        String nombre = solicitud.nombre().trim().replaceAll("\\s+", " ");
        if (clientes.existsByNombreIgnoreCase(nombre)) {
            throw Errores.clienteYaExiste();
        }
        if (usuarios.findByUsuario(solicitud.usuario()).isPresent()) {
            throw Errores.usuarioYaExiste();
        }
        long id = jdbc.sql("select nextval('clientes.cliente_alta_seq')").query(Long.class).single();
        LocalDateTime ahora = LocalDateTime.now(reloj);
        Cliente cliente = clientes.saveAndFlush(Cliente.alta(id, nombre, solicitud.edad(), solicitud.perfil().comoPerfil(), ahora));
        usuarios.save(Usuario.cliente(solicitud.usuario(), codificador.cifrar(solicitud.claveInicial()), id));
        log.info("Alta del cliente {} ({}) por el ejecutivo {}", id, nombre, quien.usuario());
        return ClienteDto.de(cliente, solicitud.usuario());
    }

    private ClienteDto dto(Cliente cliente) {
        return ClienteDto.de(cliente, usuarios.findFirstByClienteId(cliente.getId()).map(Usuario::getUsuario).orElse(null));
    }
}
