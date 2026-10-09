package com.bancoxyz.bff.movil.proxy;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import com.bancoxyz.bff.movil.config.PropiedadesMovil;
import com.bancoxyz.bff.movil.core.ClienteCoreHttp;
import com.bancoxyz.bff.movil.core.CoreApi;
import com.bancoxyz.bff.movil.core.CuentaCore;
import com.bancoxyz.bff.movil.core.PaginaMovimientosCore;
import com.bancoxyz.bff.movil.core.PagoCore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Proxy de cache con la misma interfaz que el cliente real del core.
 *
 * <p>Es el patron Proxy tal como lo describe la guia: se interpone entre el BFF y el servicio para cachear
 * respuestas. La app abre la portada muchas veces seguidas (al volver de otra app, al bajar para refrescar); durante
 * 10 segundos las cuentas y las paginas de movimientos se sirven desde memoria sin ir al core. Una portada repetida
 * no genera ninguna llamada al core.</p>
 *
 * <p>Por que no afecta la integridad: solo cachea LECTURAS del canal movil, con el usuario en la clave (el core
 * autoriza por usuario y la cache no puede saltarse esa decision). Lo que ve la app puede tener hasta 10 s de
 * antiguedad; el cajero y el core leen siempre fresco, y ningun retiro decide con este dato. Los errores del core no
 * se cachean.</p>
 */
@Primary
@Component
public class ProxyCoreConCache implements CoreApi {

    private record Entrada(Object valor, Instant vence) {
    }

    private final CoreApi real;
    private final Clock reloj;
    private final long vidaSegundos;
    private final Map<String, Entrada> lecturas = new ConcurrentHashMap<>();
    private final AtomicLong ultimoDesalojo = new AtomicLong();

    @Autowired
    public ProxyCoreConCache(ClienteCoreHttp real, Clock reloj, PropiedadesMovil propiedades) {
        this(real, reloj, propiedades.cacheCuentasSegundos());
    }

    /** Para probar el proxy con un core simulado que cuenta llamadas. */
    ProxyCoreConCache(CoreApi real, Clock reloj, long vidaSegundos) {
        this.real = real;
        this.reloj = reloj;
        this.vidaSegundos = vidaSegundos;
    }

    @Override
    public List<CuentaCore> cuentasDeCliente(long clienteId, long usuarioId, String correlacion) {
        return leer("cuentas|" + usuarioId + "|" + clienteId, () -> real.cuentasDeCliente(clienteId, usuarioId, correlacion));
    }

    @Override
    public PaginaMovimientosCore movimientos(long cuentaId, long usuarioId, int pagina, int tamano, String correlacion) {
        return leer("movimientos|" + usuarioId + "|" + cuentaId + "|" + pagina + "|" + tamano,
                () -> real.movimientos(cuentaId, usuarioId, pagina, tamano, correlacion));
    }

    /** Las escrituras y el estado de un pago no se guardan en cache: siempre van al servicio. */
    @Override
    public PagoCore transferir(long origen, long destino, java.math.BigDecimal monto, String clave, String correlacion) {
        return real.transferir(origen, destino, monto, clave, correlacion);
    }

    @Override
    public PagoCore pago(String id, String correlacion) {
        return real.pago(id, correlacion);
    }

    public void vaciar() {
        lecturas.clear();
    }

    int entradas() {
        return lecturas.size();
    }

    @SuppressWarnings("unchecked")
    private <T> T leer(String clave, Supplier<T> delCore) {
        Instant ahora = reloj.instant();
        Entrada entrada = lecturas.get(clave);
        if (entrada != null && entrada.vence().isAfter(ahora)) {
            return (T) entrada.valor();
        }
        T valor = delCore.get();
        desalojarVencidas(ahora);
        lecturas.put(clave, new Entrada(valor, ahora.plusSeconds(vidaSegundos)));
        return valor;
    }

    /** Como mucho una vez por segundo: sin esto el mapa creceria con cada usuario que alguna vez abrio la app. */
    private void desalojarVencidas(Instant ahora) {
        long segundo = ahora.getEpochSecond();
        long anterior = ultimoDesalojo.get();
        if (segundo > anterior && ultimoDesalojo.compareAndSet(anterior, segundo)) {
            lecturas.values().removeIf(e -> !e.vence().isAfter(ahora));
        }
    }
}
