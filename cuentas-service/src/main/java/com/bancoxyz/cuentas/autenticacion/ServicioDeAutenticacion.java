package com.bancoxyz.cuentas.autenticacion;

import java.util.Optional;

import com.bancoxyz.cuentas.alerta.AlertaDeSeguridad;
import com.bancoxyz.cuentas.cuenta.RepositorioCuentas;
import com.bancoxyz.cuentas.outbox.Outbox;
import com.bancoxyz.cuentas.pago.Topicos;
import com.bancoxyz.cuentas.tarjeta.RepositorioTarjetas;
import com.bancoxyz.cuentas.tarjeta.Tarjeta;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * Verifica tarjeta y PIN para el BFF del cajero.
 *
 * <p>Devuelve un resultado en vez de lanzar: el intento fallido debe quedar guardado aunque la respuesta sea un error
 * (con una excepcion la transaccion se revertiria y el contador volveria a cero). El contador vive aqui y no en el BFF:
 * cambiar de cajero no da intentos nuevos. Al tercer PIN incorrecto la tarjeta se bloquea y se publica una alerta.</p>
 */
@Service
public class ServicioDeAutenticacion {

    static final int MAXIMO_INTENTOS_PIN = 3;

    public enum EstadoTarjeta { AUTENTICADA, TARJETA_INVALIDA, PIN_INCORRECTO, BLOQUEADA }

    public record ResultadoTarjeta(EstadoTarjeta estado, Tarjeta tarjeta, int intentosRestantes) {
    }

    private final RepositorioTarjetas tarjetas;
    private final RepositorioCuentas cuentas;
    private final CodificadorDeSecretos codificador;
    private final Outbox outbox;
    private final Clock reloj;

    public ServicioDeAutenticacion(RepositorioTarjetas tarjetas, RepositorioCuentas cuentas,
                                   CodificadorDeSecretos codificador, Outbox outbox, Clock reloj) {
        this.tarjetas = tarjetas;
        this.cuentas = cuentas;
        this.codificador = codificador;
        this.outbox = outbox;
        this.reloj = reloj;
    }

    @Transactional
    public ResultadoTarjeta autenticarTarjeta(String numero, String pin) {
        Optional<Tarjeta> encontrada = tarjetas.buscarPorHuellaParaActualizar(codificador.huellaDeTarjeta(numero));
        if (encontrada.isEmpty()) {
            codificador.verificarContraFicticio(pin);
            return new ResultadoTarjeta(EstadoTarjeta.TARJETA_INVALIDA, null, 0);
        }
        Tarjeta tarjeta = encontrada.get();
        if (tarjeta.isBloqueada()) {
            return new ResultadoTarjeta(EstadoTarjeta.BLOQUEADA, tarjeta, 0);
        }
        if (codificador.coincide(pin, tarjeta.getPinHash())) {
            tarjeta.registrarExito();
            return new ResultadoTarjeta(EstadoTarjeta.AUTENTICADA, tarjeta, MAXIMO_INTENTOS_PIN);
        }
        tarjeta.registrarFallo(MAXIMO_INTENTOS_PIN);
        if (tarjeta.isBloqueada()) {
            Long clienteId = cuentas.findById(tarjeta.getCuentaId()).map(c -> c.getClienteId()).orElse(null);
            AlertaDeSeguridad alerta = AlertaDeSeguridad.tarjetaBloqueada(clienteId, tarjeta.getCuentaId(),
                    tarjeta.getUltimos4(), reloj.instant());
            outbox.registrar(Topicos.SEGURIDAD_ALERTAS, alerta.eventoId(), alerta.clave(), alerta.tipo(), alerta);
            return new ResultadoTarjeta(EstadoTarjeta.BLOQUEADA, tarjeta, 0);
        }
        return new ResultadoTarjeta(EstadoTarjeta.PIN_INCORRECTO, tarjeta, MAXIMO_INTENTOS_PIN - tarjeta.getIntentosFallidos());
    }
}
