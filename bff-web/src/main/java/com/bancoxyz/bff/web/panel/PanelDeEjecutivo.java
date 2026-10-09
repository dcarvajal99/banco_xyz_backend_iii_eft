package com.bancoxyz.bff.web.panel;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import com.bancoxyz.bff.web.adaptador.AdaptadorWeb;
import com.bancoxyz.bff.web.config.PropiedadesWeb;
import com.bancoxyz.bff.web.core.CalidadCore;
import com.bancoxyz.bff.web.core.ClienteCoreWeb;
import com.bancoxyz.bff.web.core.ClienteDelBancoCore;
import com.bancoxyz.bff.web.core.ResumenDiarioCore;
import com.bancoxyz.bff.web.seguridad.UsuarioWeb;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(1)
public class PanelDeEjecutivo implements EstrategiaDePanel {

    static final int DIAS_DE_RESUMEN = 30;

    private final ClienteCoreWeb core;
    private final AdaptadorWeb adaptador;
    private final Executor ejecutor;
    private final long timeoutSegundos;

    public PanelDeEjecutivo(ClienteCoreWeb core, AdaptadorWeb adaptador, @Qualifier("agregadorWeb") Executor ejecutor,
                            PropiedadesWeb propiedades) {
        this.core = core;
        this.adaptador = adaptador;
        this.ejecutor = ejecutor;
        this.timeoutSegundos = propiedades.agregador().timeoutSegundos();
    }

    @Override
    public boolean atiende(UsuarioWeb usuario) {
        return usuario.esEjecutivo();
    }

    @Override
    public PanelEjecutivoWeb construir(UsuarioWeb usuario, String correlacion) {
        long usuarioId = usuario.usuarioId();
        CompletableFuture<List<ClienteDelBancoCore>> clientes =
                CompletableFuture.supplyAsync(() -> core.clientes(usuarioId, correlacion), ejecutor);
        CompletableFuture<CalidadCore> calidad = CompletableFuture.supplyAsync(() -> core.calidad(usuarioId, correlacion), ejecutor);
        CompletableFuture<List<ResumenDiarioCore>> resumen =
                CompletableFuture.supplyAsync(() -> core.resumenDiario(usuarioId, null, null, correlacion), ejecutor);

        Espera espera = new Espera(timeoutSegundos);
        List<String> advertencias = new ArrayList<>();
        List<ClienteDelBancoCore> listaDeClientes = espera.esencial(clientes);
        CalidadCore calidadDelCierre = espera.opcional(calidad, null, advertencias, "No se pudo consultar la calidad de los cierres.");
        List<ResumenDiarioCore> filas = espera.opcional(resumen, List.of(), advertencias, "No se pudo cargar el resumen diario.");

        List<ResumenDiarioCore> ultimas = filas.subList(Math.max(0, filas.size() - DIAS_DE_RESUMEN), filas.size());
        return new PanelEjecutivoWeb(listaDeClientes.stream().map(adaptador::cliente).toList(),
                calidadDelCierre == null ? null : adaptador.calidad(calidadDelCierre),
                ultimas.stream().map(adaptador::fila).toList(),
                calidadDelCierre == null ? List.of() : adaptador.alertas(calidadDelCierre),
                advertencias);
    }
}
