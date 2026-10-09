package com.bancoxyz.bff.movil.inicio;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

import com.bancoxyz.bff.movil.adaptador.AdaptadorMovil;
import com.bancoxyz.bff.movil.config.FiltroCorrelacion;
import com.bancoxyz.bff.movil.core.CoreApi;
import com.bancoxyz.bff.movil.core.CuentaCore;
import com.bancoxyz.bff.movil.core.MovimientoCore;
import com.bancoxyz.bff.movil.seguridad.UsuarioMovil;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ControladorDeInicio {

    static final int ULTIMOS = 5;

    private final CoreApi core;
    private final AdaptadorMovil adaptador;
    private final Executor hilos;

    public ControladorDeInicio(CoreApi core, AdaptadorMovil adaptador, @Qualifier("portadaMovil") Executor hilos) {
        this.core = core;
        this.adaptador = adaptador;
        this.hilos = hilos;
    }

    /**
     * Cache-Control {@code private, no-cache}: la app puede guardar la portada en su sandbox pero debe revalidarla con
     * el ETag en cada apertura (304 sin cuerpo si no cambio). Un proxy compartido no puede guardarla.
     */
    public static final CacheControl REVALIDAR_EN_LA_APP = CacheControl.noCache().cachePrivate();

    @Operation(summary = "Portada de la app: disponible, deuda, cuentas y 5 ultimos movimientos")
    @GetMapping("/movil/v1/inicio")
    public ResponseEntity<InicioMovil> inicio(@AuthenticationPrincipal Jwt jwt) {
        UsuarioMovil usuario = UsuarioMovil.desde(jwt);
        String correlacion = FiltroCorrelacion.actual();
        List<CuentaCore> cuentas = core.cuentasDeCliente(usuario.clienteId(), usuario.usuarioId(), correlacion);
        // Los 5 mas recientes de TODAS las cuentas: basta la primera pagina de 5 de cada una, pedidas en paralelo.
        // La identidad y la correlacion se capturan aqui y viajan como parametros a cada tarea.
        long usuarioId = usuario.usuarioId();
        List<CompletableFuture<List<MovimientoCore>>> paginas = cuentas.stream()
                .map(cuenta -> CompletableFuture.supplyAsync(
                        () -> core.movimientos(cuenta.cuentaId(), usuarioId, 0, ULTIMOS, correlacion).contenido(), hilos))
                .toList();
        List<MovimientoCore> recientes = new ArrayList<>();
        for (CompletableFuture<List<MovimientoCore>> pagina : paginas) {
            recientes.addAll(resultado(pagina));
        }
        return ResponseEntity.ok().cacheControl(REVALIDAR_EN_LA_APP).body(new InicioMovil(AdaptadorMovil.primerNombre(usuario.nombre()),
                adaptador.disponible(cuentas), adaptador.deuda(cuentas), cuentas.stream().map(adaptador::cuenta).toList(),
                adaptador.ultimos(recientes, ULTIMOS)));
    }

    /** join() envuelve el error del core en CompletionException: se desenvuelve para que lo traduzca el manejador. */
    private static <T> T resultado(CompletableFuture<T> tarea) {
        try {
            return tarea.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException causa) {
                throw causa;
            }
            throw e;
        }
    }
}
