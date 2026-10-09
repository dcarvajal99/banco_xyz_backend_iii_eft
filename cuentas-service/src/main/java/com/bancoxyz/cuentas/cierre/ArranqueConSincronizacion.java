package com.bancoxyz.cuentas.cierre;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Sincroniza el cierre publicado ANTES de que Tomcat acepte solicitudes (SmartInitializingSingleton): con un
 * ApplicationRunner el health respondia UP mientras las cuentas aun se estaban abriendo.
 */
@Component
@ConditionalOnProperty(name = "banco.cuentas.sincronizar-al-iniciar", havingValue = "true")
public class ArranqueConSincronizacion implements SmartInitializingSingleton {

    private final SincronizadorDeCierre sincronizador;

    public ArranqueConSincronizacion(SincronizadorDeCierre sincronizador) {
        this.sincronizador = sincronizador;
    }

    @Override
    public void afterSingletonsInstantiated() {
        sincronizador.sincronizar();
    }
}
