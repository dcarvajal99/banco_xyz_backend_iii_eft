package com.bancoxyz.bff.web.panel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import com.bancoxyz.bff.web.adaptador.AdaptadorWeb;
import com.bancoxyz.bff.web.config.PropiedadesWeb;
import com.bancoxyz.bff.web.core.CierreCore;
import com.bancoxyz.bff.web.core.ClienteCoreWeb;
import com.bancoxyz.bff.web.core.CuentaCore;
import com.bancoxyz.bff.web.core.EstadoAnualCore;
import com.bancoxyz.bff.web.core.MovimientoCore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Arma el panel de un cliente juntando varias respuestas del core en paralelo.
 *
 * <p>Las cuentas se piden primero y en el hilo de la solicitud: son esenciales y definen que mas pedir. Despues, en
 * paralelo, los movimientos y los estados anuales de cada cuenta y el cierre vigente. Con 8 cuentas son 17 llamadas;
 * en serie el panel tardaria la suma, en paralelo tarda la mas lenta.</p>
 *
 * <p>La identidad (usuarioId) y la correlacion se capturan aqui y viajan como parametros a cada tarea: los hilos del
 * pool no tienen el contexto de seguridad de esta solicitud, y no deben tenerlo.</p>
 */
@Component
public class AgregadorDeCliente {

    static final int MOVIMIENTOS_POR_CUENTA = 20;
    static final int MOVIMIENTOS_RECIENTES = 20;

    private final ClienteCoreWeb core;
    private final AdaptadorWeb adaptador;
    private final Executor ejecutor;
    private final long timeoutSegundos;

    public AgregadorDeCliente(ClienteCoreWeb core, AdaptadorWeb adaptador, @Qualifier("agregadorWeb") Executor ejecutor,
                              PropiedadesWeb propiedades) {
        this.core = core;
        this.adaptador = adaptador;
        this.ejecutor = ejecutor;
        this.timeoutSegundos = propiedades.agregador().timeoutSegundos();
    }

    public PanelClienteWeb panel(long usuarioId, long clienteId, String titular, String correlacion) {
        List<CuentaCore> cuentas = core.cuentasDeCliente(clienteId, usuarioId, correlacion);
        Espera espera = new Espera(timeoutSegundos);

        Map<Long, CompletableFuture<List<MovimientoCore>>> movimientos = new LinkedHashMap<>();
        Map<Long, CompletableFuture<List<EstadoAnualCore>>> estados = new LinkedHashMap<>();
        for (CuentaCore cuenta : cuentas) {
            long cuentaId = cuenta.cuentaId();
            movimientos.put(cuentaId, CompletableFuture.supplyAsync(
                    () -> core.movimientos(cuentaId, usuarioId, 0, MOVIMIENTOS_POR_CUENTA, correlacion).contenido(), ejecutor));
            estados.put(cuentaId, CompletableFuture.supplyAsync(
                    () -> core.estadosAnuales(cuentaId, usuarioId, correlacion), ejecutor));
        }
        CompletableFuture<List<CierreCore>> cierres = CompletableFuture.supplyAsync(() -> core.cierresVigentes(correlacion), ejecutor);

        List<String> advertencias = new ArrayList<>();
        List<MovimientoCore> todos = new ArrayList<>();
        movimientos.forEach((cuentaId, tarea) -> todos.addAll(espera.opcional(tarea, List.of(), advertencias,
                "No se pudieron cargar los movimientos de la cuenta " + cuentaId + ".")));
        List<EstadoAnualCore> anuales = new ArrayList<>();
        estados.forEach((cuentaId, tarea) -> anuales.addAll(espera.opcional(tarea, List.of(), advertencias,
                "No se pudieron cargar los estados anuales de la cuenta " + cuentaId + ".")));
        List<CierreCore> vigentes = espera.opcional(cierres, List.of(), advertencias,
                "No se pudo consultar el estado del cierre de datos.");

        return new PanelClienteWeb(titular, adaptador.resumen(cuentas), cuentas.stream().map(adaptador::cuenta).toList(),
                adaptador.recientes(todos, MOVIMIENTOS_RECIENTES), anuales.stream().map(adaptador::estado).toList(),
                adaptador.cierrePrincipal(vigentes), advertencias);
    }
}
