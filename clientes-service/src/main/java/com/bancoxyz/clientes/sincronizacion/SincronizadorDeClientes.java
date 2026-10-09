package com.bancoxyz.clientes.sincronizacion;

import java.text.Normalizer;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.bancoxyz.clientes.autenticacion.CodificadorDeSecretos;
import com.bancoxyz.clientes.config.PropiedadesClientes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Crea en el esquema {@code clientes} los clientes del maestro que publico el batch ({@code migracion.cliente_migrado}).
 *
 * <ul>
 *   <li><b>Mismo id que la migracion.</b> cuentas-service copia el mismo id en cada cuenta: los dos servicios quedan
 *       consistentes sin llamarse entre si.</li>
 *   <li><b>Un cliente existente no se toca.</b> Su perfil ya lo mantiene el propio cliente; volver a copiar el maestro no
 *       debe borrar un correo que el cliente cambio ayer.</li>
 *   <li><b>Segura con varias instancias.</b> {@code insert ... on conflict do nothing}: si dos replicas arrancan a la vez,
 *       la segunda no falla ni duplica.</li>
 * </ul>
 *
 * <p>Con los datos de demostracion habilitados crea ademas un usuario por cliente ({@code diana.prince}, clave comun) y el
 * usuario {@code ejecutivo}.</p>
 */
@Component
public class SincronizadorDeClientes {

    static final String USUARIO_EJECUTIVO = "ejecutivo";

    private static final Logger log = LoggerFactory.getLogger(SincronizadorDeClientes.class);

    private record ClienteMigrado(long id, String nombre, int edad) {
    }

    private final JdbcClient jdbc;
    private final CodificadorDeSecretos codificador;
    private final PropiedadesClientes propiedades;
    private final Clock reloj;

    public SincronizadorDeClientes(JdbcClient jdbc, CodificadorDeSecretos codificador, PropiedadesClientes propiedades,
                                   Clock reloj) {
        this.jdbc = jdbc;
        this.codificador = codificador;
        this.propiedades = propiedades;
        this.reloj = reloj;
    }

    @Transactional
    public ResumenSincronizacion sincronizar() {
        List<ClienteMigrado> migrados;
        try {
            migrados = jdbc.sql("select id, nombre, edad from migracion.cliente_migrado order by id")
                    .query(ClienteMigrado.class).list();
        } catch (BadSqlGrammarException sinMigracion) {
            log.warn("Sin maestro de clientes: migracion.cliente_migrado no existe. Ejecute el batch y reinicie el servicio.");
            return ResumenSincronizacion.sinMigracion();
        }
        LocalDateTime ahora = LocalDateTime.now(reloj).truncatedTo(ChronoUnit.SECONDS);
        int nuevos = 0;
        for (ClienteMigrado migrado : migrados) {
            nuevos += jdbc.sql("""
                            insert into clientes.cliente (id, nombre, edad, origen, creado_en, actualizado_en)
                            values (:id, :nombre, :edad, 'MIGRACION', :ahora, :ahora) on conflict do nothing""")
                    .param("id", migrado.id()).param("nombre", migrado.nombre()).param("edad", migrado.edad())
                    .param("ahora", ahora).update();
        }

        int usuariosNuevos = 0;
        List<String> credenciales = new ArrayList<>();
        if (propiedades.datosDemo().habilitados()) {
            String claveClientes = codificador.cifrar(propiedades.datosDemo().claveClientes());
            for (ClienteMigrado migrado : migrados) {
                String usuario = nombreDeUsuario(migrado.nombre());
                usuariosNuevos += crearUsuario(usuario, claveClientes, "CLIENTE", migrado.id());
                credenciales.add("  usuario %-16s cliente %d %s".formatted(usuario, migrado.id(), migrado.nombre()));
            }
            usuariosNuevos += crearUsuario(USUARIO_EJECUTIVO, codificador.cifrar(propiedades.datosDemo().claveEjecutivo()),
                    "EJECUTIVO", null);
            credenciales.add("  usuario %-16s ejecutivo (consola web)".formatted(USUARIO_EJECUTIVO));
        }

        long total = jdbc.sql("select count(*) from clientes.cliente").query(Long.class).single();
        log.info("Maestro de clientes: {} en la migracion, {} nuevos, {} en total | {} usuarios nuevos",
                migrados.size(), nuevos, total, usuariosNuevos);
        if (!credenciales.isEmpty()) {
            log.info("Usuarios de demostracion (claves en el README):\n{}", String.join("\n", credenciales));
        }
        return new ResumenSincronizacion(true, migrados.size(), nuevos, usuariosNuevos);
    }

    private int crearUsuario(String usuario, String hash, String rol, Long clienteId) {
        return jdbc.sql("""
                        insert into clientes.usuario (usuario, password_hash, rol, cliente_id, activo, intentos_fallidos)
                        values (:usuario, :hash, :rol, :cliente, true, 0) on conflict do nothing""")
                .param("usuario", usuario).param("hash", hash).param("rol", rol).param("cliente", clienteId)
                .update();
    }

    /** "Diana Prince" -> "diana.prince". */
    public static String nombreDeUsuario(String nombre) {
        String sinTildes = Normalizer.normalize(nombre, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return sinTildes.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", ".").replaceAll("[^a-z0-9.]", "");
    }
}
