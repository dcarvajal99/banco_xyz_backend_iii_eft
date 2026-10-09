package com.bancoxyz.cuentas.seguridad;

import java.util.Objects;

import com.bancoxyz.cuentas.cuenta.Cuenta;
import com.bancoxyz.cuentas.cuenta.RepositorioCuentas;
import com.bancoxyz.cuentas.error.Errores;
import com.bancoxyz.cuentas.tarjeta.RepositorioTarjetas;
import com.bancoxyz.cuentas.tarjeta.Tarjeta;
import org.springframework.stereotype.Component;

/**
 * Segunda capa de autorizacion (la primera es el scope en ConfiguracionSeguridad): que cuenta puede ver quien.
 * Ajena o inexistente responden igual (404) para no confirmar que una cuenta existe.
 */
@Component
public class ControlDeAcceso {

    public static final String ENCABEZADO_TARJETA = "X-Tarjeta-Id";
    public static final String ENCABEZADO_TERMINAL = "X-Terminal-Id";

    private final RepositorioCuentas cuentas;
    private final RepositorioTarjetas tarjetas;

    public ControlDeAcceso(RepositorioCuentas cuentas, RepositorioTarjetas tarjetas) {
        this.cuentas = cuentas;
        this.tarjetas = tarjetas;
    }

    /** Ejecutivo y servicios ven cualquier cuenta; un cliente, solo las suyas. */
    public Cuenta cuentaVisible(IdentidadDelToken quien, long cuentaId) {
        Cuenta cuenta = cuentas.findById(cuentaId).orElseThrow(Errores::cuentaNoEncontrada);
        if (quien.esDeUsuario() && !quien.esEjecutivo() && !Objects.equals(cuenta.getClienteId(), quien.clienteId())) {
            throw Errores.cuentaNoEncontrada();
        }
        return cuenta;
    }

    public void exigirClienteVisible(IdentidadDelToken quien, long clienteId) {
        if (!quien.esEjecutivo() && !Objects.equals(quien.clienteId(), clienteId)) {
            throw Errores.clienteNoEncontrado();
        }
    }

    /** Tarjeta que el BFF del cajero ya autentico con PIN; el terminal queda en la operacion. */
    public Tarjeta tarjeta(String tarjetaId, String terminalId) {
        if (terminalId == null || terminalId.isBlank() || terminalId.length() > 40) {
            throw Errores.identidadDeCajeroRequerida(ENCABEZADO_TERMINAL);
        }
        try {
            return tarjetas.findById(Long.parseLong(String.valueOf(tarjetaId).trim()))
                    .orElseThrow(Errores::accesoDenegado);
        } catch (NumberFormatException e) {
            throw Errores.identidadDeCajeroRequerida(ENCABEZADO_TARJETA);
        }
    }

    /** El cajero solo ve la cuenta de la tarjeta autenticada. */
    public Cuenta cuentaDeTarjeta(Tarjeta tarjeta, long cuentaId) {
        if (!Objects.equals(tarjeta.getCuentaId(), cuentaId)) {
            throw Errores.cuentaNoEncontrada();
        }
        return cuentas.findById(cuentaId).orElseThrow(Errores::cuentaNoEncontrada);
    }
}
