package com.bancoxyz.clientes.sincronizacion;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Copia el maestro de clientes al arrancar. En el compose el batch ya termino: el servicio depende de el. */
@Component
@ConditionalOnProperty(name = "banco.clientes.sincronizar-al-iniciar", havingValue = "true", matchIfMissing = true)
public class ArranqueConSincronizacion implements ApplicationRunner {

    private final SincronizadorDeClientes sincronizador;

    public ArranqueConSincronizacion(SincronizadorDeClientes sincronizador) {
        this.sincronizador = sincronizador;
    }

    @Override
    public void run(ApplicationArguments argumentos) {
        sincronizador.sincronizar();
    }
}
