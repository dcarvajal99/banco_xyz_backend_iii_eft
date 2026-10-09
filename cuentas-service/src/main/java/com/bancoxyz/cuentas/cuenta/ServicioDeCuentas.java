package com.bancoxyz.cuentas.cuenta;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import com.bancoxyz.cuentas.cierre.Conjunto;
import com.bancoxyz.cuentas.cierre.LectorDeCierres;
import com.bancoxyz.cuentas.clientes.ClienteDeClientes;
import com.bancoxyz.cuentas.config.PropiedadesCuentas;
import com.bancoxyz.cuentas.error.Errores;
import com.bancoxyz.cuentas.seguridad.ControlDeAcceso;
import com.bancoxyz.cuentas.seguridad.IdentidadDelToken;
import com.bancoxyz.cuentas.tarjeta.RepositorioTarjetas;
import com.bancoxyz.cuentas.tarjeta.Tarjeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Consultas, apertura, mantenimiento y cierre de cuentas, siempre con la identidad del token. */
@Service
public class ServicioDeCuentas {

    static final int TAMANO_MAXIMO = 100;
    private static final Logger log = LoggerFactory.getLogger(ServicioDeCuentas.class);

    private final ControlDeAcceso acceso;
    private final RepositorioCuentas cuentas;
    private final RepositorioTarjetas tarjetas;
    private final LectorDeCierres cierres;
    private final LectorDeMovimientos movimientos;
    private final ClienteDeClientes clientes;
    private final PropiedadesCuentas propiedades;
    private final JdbcClient jdbc;
    private final Clock reloj;

    public ServicioDeCuentas(ControlDeAcceso acceso, RepositorioCuentas cuentas, RepositorioTarjetas tarjetas,
                             LectorDeCierres cierres, LectorDeMovimientos movimientos, ClienteDeClientes clientes,
                             PropiedadesCuentas propiedades, JdbcClient jdbc, Clock reloj) {
        this.acceso = acceso;
        this.cuentas = cuentas;
        this.tarjetas = tarjetas;
        this.cierres = cierres;
        this.movimientos = movimientos;
        this.clientes = clientes;
        this.propiedades = propiedades;
        this.jdbc = jdbc;
        this.reloj = reloj;
    }

    @Transactional(readOnly = true)
    public List<CuentaDto> cuentasDeCliente(IdentidadDelToken quien, long clienteId) {
        acceso.exigirClienteVisible(quien, clienteId);
        return cuentas.findByClienteIdOrderByIdAsc(clienteId).stream().map(CuentaDto::desde).toList();
    }

    /** Usuario y servicios por token; el cajero, por la tarjeta que autentico. */
    @Transactional(readOnly = true)
    public CuentaDto cuenta(IdentidadDelToken quien, String tarjetaId, String terminalId, long cuentaId) {
        if (quien.tieneScope("cajero.operar") && !quien.esDeUsuario()) {
            Tarjeta tarjeta = acceso.tarjeta(tarjetaId, terminalId);
            return CuentaDto.desde(acceso.cuentaDeTarjeta(tarjeta, cuentaId));
        }
        if (!quien.tieneScope("cuentas.leer")) {
            throw Errores.accesoDenegado();
        }
        return CuentaDto.desde(acceso.cuentaVisible(quien, cuentaId));
    }

    @Transactional(readOnly = true)
    public PaginaMovimientos movimientos(IdentidadDelToken quien, long cuentaId, int pagina, int tamano) {
        if (pagina < 0 || tamano < 1 || tamano > TAMANO_MAXIMO) {
            throw Errores.solicitudInvalida("pagina debe ser >= 0 y tamano entre 1 y " + TAMANO_MAXIMO);
        }
        acceso.cuentaVisible(quien, cuentaId);
        Long job = cierres.vigente(Conjunto.MOVIMIENTOS).map(c -> c.jobExecutionId()).orElse(-1L);
        return movimientos.pagina(cuentaId, job, pagina, tamano);
    }

    @Transactional(readOnly = true)
    public List<EstadoAnualDto> estadosAnuales(IdentidadDelToken quien, long cuentaId) {
        acceso.cuentaVisible(quien, cuentaId);
        long job = cierres.exigirVigente(Conjunto.MOVIMIENTOS).jobExecutionId();
        return movimientos.estadosAnuales(cuentaId, job);
    }

    /** Apertura por un ejecutivo: el titular se confirma en clientes-service (con respaldo, ver ClienteDeClientes). */
    @Transactional
    public CuentaDto abrir(IdentidadDelToken quien, SolicitudApertura solicitud) {
        quien.exigirEjecutivo();
        ClienteDeClientes.Titular titular = clientes.titular(solicitud.clienteId());
        long id = jdbc.sql("select nextval('cuentas.cuenta_seq')").query(Long.class).single();
        BigDecimal tasa = propiedades.tasas().getOrDefault(solicitud.tipo(), BigDecimal.ZERO);
        Cuenta cuenta = cuentas.save(Cuenta.abierta(id, solicitud.clienteId(), titular.nombre(), solicitud.tipo(),
                solicitud.alias(), tasa, ahora()));
        log.info("Cuenta {} ({}) abierta para el cliente {} por {} | titular verificado en linea: {}", id,
                solicitud.tipo(), solicitud.clienteId(), quien.sujeto(), titular.verificadoEnLinea());
        return CuentaDto.desde(cuenta);
    }

    @Transactional
    public CuentaDto mantener(IdentidadDelToken quien, long cuentaId, SolicitudMantenimiento solicitud) {
        quien.exigirEjecutivo();
        Cuenta cuenta = cuentas.buscarParaActualizar(cuentaId).orElseThrow(Errores::cuentaNoEncontrada);
        if (cuenta.estaCerrada()) {
            throw Errores.cuentaCerrada();
        }
        if (solicitud.alias() != null) {
            cuenta.cambiarAlias(solicitud.alias().isBlank() ? null : solicitud.alias().trim(), ahora());
        }
        if (solicitud.estado() != null) {
            cuenta.cambiarEstado(solicitud.estado(), ahora());
        }
        return CuentaDto.desde(cuentas.saveAndFlush(cuenta));
    }

    /** Solo con saldo cero; sus tarjetas quedan bloqueadas. La fila se conserva (historial y auditoria). */
    @Transactional
    public CuentaDto cerrar(IdentidadDelToken quien, long cuentaId, SolicitudCierre solicitud) {
        quien.exigirEjecutivo();
        Cuenta cuenta = cuentas.buscarParaActualizar(cuentaId).orElseThrow(Errores::cuentaNoEncontrada);
        if (cuenta.estaCerrada()) {
            throw Errores.cuentaCerrada();
        }
        if (cuenta.getSaldoDisponible().signum() != 0) {
            throw Errores.cuentaConSaldo();
        }
        cuenta.cerrar(solicitud.motivo(), ahora());
        tarjetas.findByCuentaId(cuentaId).forEach(Tarjeta::bloquear);
        log.info("Cuenta {} cerrada por {}: {}", cuentaId, quien.sujeto(), solicitud.motivo());
        return CuentaDto.desde(cuentas.saveAndFlush(cuenta));
    }

    private LocalDateTime ahora() {
        return LocalDateTime.now(reloj).truncatedTo(ChronoUnit.SECONDS);
    }
}
