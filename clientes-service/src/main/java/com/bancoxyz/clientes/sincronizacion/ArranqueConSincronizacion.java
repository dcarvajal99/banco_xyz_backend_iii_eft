package com.bancoxyz.clientes.sincronizacion;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Copia el maestro de clientes ANTES de que Tomcat acepte solicitudes (SmartInitializingSingleton), para que el health
 * no responda UP con los clientes a medio copiar. En el compose el batch ya termino: el servicio depende de el.
 */
@Component
@ConditionalOnProperty(name = "banco.clientes.sincronizar-al-iniciar", havingValue = "true", matchIfMissing = true)
public class ArranqueConSincronizacion implements SmartInitializingSingleton {

    private final SincronizadorDeClientes sincronizador;

    public ArranqueConSincronizacion(SincronizadorDeClientes sincronizador) {
        this.sincronizador = sincronizador;
    }

    @Override
    public void afterSingletonsInstantiated() {
        sincronizador.sincronizar();
    }
}
