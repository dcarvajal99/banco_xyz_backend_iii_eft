package com.bancoxyz.clientes.autenticacion;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import com.bancoxyz.clientes.alerta.AlertaDeSeguridad;
import com.bancoxyz.clientes.alerta.Topicos;
import com.bancoxyz.clientes.outbox.Outbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifica credenciales de usuario para banco-auth.
 *
 * <h2>Por que devuelve un resultado y no lanza excepciones</h2>
 * <p>Un intento fallido tiene que QUEDAR REGISTRADO aunque la respuesta sea un error. Si el servicio lanzara una
 * excepcion de negocio, la transaccion se revertiria y el contador de intentos volveria a cero: bastaria con fallar una
 * y otra vez para no bloquear nunca. Por eso confirma la transaccion y el controlador traduce el resultado despues.</p>
 *
 * <h2>Alerta de seguridad</h2>
 * <p>Al quinto intento fallido el usuario se bloquea 15 minutos y se registra un evento {@code UsuarioBloqueado} en el
 * outbox, en la misma transaccion: el bloqueo y su alerta quedan juntos o no queda ninguno. El publicador lo lleva al
 * topico {@value Topicos#SEGURIDAD_ALERTAS} y notificaciones-service avisa al cliente.</p>
 */
@Service
public class ServicioDeAutenticacion {

    static final int MAXIMO_INTENTOS_USUARIO = 5;
    static final Duration DURACION_BLOQUEO_USUARIO = Duration.ofMinutes(15);

    private static final Logger log = LoggerFactory.getLogger(ServicioDeAutenticacion.class);

    public enum EstadoUsuario { AUTENTICADO, CREDENCIALES_INVALIDAS, BLOQUEADO }

    public record ResultadoUsuario(EstadoUsuario estado, Usuario usuario) {
    }

    public enum EstadoIdentificacion { IDENTIFICADO, NO_ENCONTRADO, BLOQUEADO, ROL_NO_PERMITIDO }

    public record ResultadoIdentificacion(EstadoIdentificacion estado, Usuario usuario) {
    }

    private final RepositorioUsuarios usuarios;
    private final CodificadorDeSecretos codificador;
    private final Outbox outbox;
    private final Clock reloj;

    public ServicioDeAutenticacion(RepositorioUsuarios usuarios, CodificadorDeSecretos codificador, Outbox outbox,
                                   Clock reloj) {
        this.usuarios = usuarios;
        this.codificador = codificador;
        this.outbox = outbox;
        this.reloj = reloj;
    }

    /**
     * Clave de un cliente o de un ejecutivo. Que rol puede entrar por que aplicacion lo decide banco-auth segun el
     * cliente OAuth (la banca movil no admite ejecutivos); aqui solo se verifica la credencial.
     */
    @Transactional
    public ResultadoUsuario autenticarUsuario(String nombreUsuario, String clave) {
        Optional<Usuario> encontrado = usuarios.buscarParaActualizar(nombreUsuario);
        if (encontrado.isEmpty() || !encontrado.get().isActivo()) {
            codificador.verificarContraFicticio(clave);
            return new ResultadoUsuario(EstadoUsuario.CREDENCIALES_INVALIDAS, null);
        }
        Usuario usuario = encontrado.get();
        LocalDateTime ahora = LocalDateTime.now(reloj);
        // Con un bloqueo vigente la clave ni se evalua: si no, un atacante sabria cuando acerto.
        if (usuario.bloqueadoEn(ahora)) {
            return new ResultadoUsuario(EstadoUsuario.BLOQUEADO, usuario);
        }
        usuario.liberarBloqueoVencido(ahora);
        if (!codificador.coincide(clave, usuario.getPasswordHash())) {
            usuario.registrarFallo(ahora, MAXIMO_INTENTOS_USUARIO, DURACION_BLOQUEO_USUARIO);
            if (usuario.bloqueadoEn(ahora)) {
                outbox.registrar(Topicos.SEGURIDAD_ALERTAS, AlertaDeSeguridad.usuarioBloqueado(usuario, ahora,
                        DURACION_BLOQUEO_USUARIO, reloj));
                log.warn("Usuario {} bloqueado por {} intentos fallidos: alerta de seguridad registrada",
                        usuario.getUsuario(), MAXIMO_INTENTOS_USUARIO);
                return new ResultadoUsuario(EstadoUsuario.BLOQUEADO, usuario);
            }
            return new ResultadoUsuario(EstadoUsuario.CREDENCIALES_INVALIDAS, usuario);
        }
        usuario.registrarExito();
        return new ResultadoUsuario(EstadoUsuario.AUTENTICADO, usuario);
    }

    /**
     * Identifica a un usuario por su nombre, sin clave. Lo usa banco-auth cuando la persona ya se autentico con un
     * proveedor externo (GitHub) cuya cuenta esta vinculada a este usuario. Solo clientes: un ejecutivo entra siempre
     * con su clave del banco. No toca el contador de intentos: no hay clave que probar.
     */
    @Transactional(readOnly = true)
    public ResultadoIdentificacion identificarUsuario(String nombreUsuario) {
        Optional<Usuario> encontrado = usuarios.findByUsuario(nombreUsuario);
        if (encontrado.isEmpty() || !encontrado.get().isActivo()) {
            return new ResultadoIdentificacion(EstadoIdentificacion.NO_ENCONTRADO, null);
        }
        Usuario usuario = encontrado.get();
        if (usuario.bloqueadoEn(LocalDateTime.now(reloj))) {
            return new ResultadoIdentificacion(EstadoIdentificacion.BLOQUEADO, usuario);
        }
        if (usuario.esEjecutivo()) {
            return new ResultadoIdentificacion(EstadoIdentificacion.ROL_NO_PERMITIDO, usuario);
        }
        return new ResultadoIdentificacion(EstadoIdentificacion.IDENTIFICADO, usuario);
    }
}
