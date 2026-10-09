package com.bancoxyz.bff.web.panel;

import com.bancoxyz.bff.web.seguridad.UsuarioWeb;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(2)
public class PanelDeCliente implements EstrategiaDePanel {

    private final AgregadorDeCliente agregador;

    public PanelDeCliente(AgregadorDeCliente agregador) {
        this.agregador = agregador;
    }

    @Override
    public boolean atiende(UsuarioWeb usuario) {
        return !usuario.esEjecutivo() && usuario.clienteId() != null;
    }

    @Override
    public PanelClienteWeb construir(UsuarioWeb usuario, String correlacion) {
        return agregador.panel(usuario.usuarioId(), usuario.clienteId(), usuario.nombre(), correlacion);
    }
}
