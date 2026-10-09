package com.bancoxyz.pagos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.bancoxyz.pagos.config.PropiedadesPagos;
import com.bancoxyz.pagos.cuentas.ClienteDeCuentas;
import com.bancoxyz.pagos.cuentas.Validacion;
import com.bancoxyz.pagos.error.Errores;
import com.bancoxyz.pagos.pago.Pago;
import com.bancoxyz.pagos.pago.ReglasDeFraude;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.web.client.RestClient;

/** Comportamiento alternativo de la prevalidacion y reglas de fraude, sin levantar Spring. */
class ClienteDeCuentasTest {

    private final PropiedadesPagos propiedades = new PropiedadesPagos("America/Santiago", "https://cuentas", List.of(),
            new PropiedadesPagos.Fraude(new BigDecimal("5000000"), Set.of(666L)));
    private final ClienteDeCuentas cliente = new ClienteDeCuentas(RestClient.builder(), propiedades,
            mock(OAuth2AuthorizedClientManager.class));

    private Validacion fallback(Exception falla) throws Exception {
        Method metodo = ClienteDeCuentas.class.getDeclaredMethod("validacionDiferida", long.class, long.class, Exception.class);
        metodo.setAccessible(true);
        try {
            return (Validacion) metodo.invoke(cliente, 4L, 101L, falla);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    @Test
    @DisplayName("Circuito abierto o sin conexion: validacion DIFERIDA, el pago no se rechaza")
    void diferida() throws Exception {
        assertThat(fallback(CallNotPermittedException.createCallNotPermittedException(CircuitBreaker.ofDefaults("cuentas"))))
                .isEqualTo(Validacion.DIFERIDA);
        assertThat(fallback(new org.springframework.web.client.ResourceAccessException("sin conexion"))).isEqualTo(Validacion.DIFERIDA);
    }

    @Test
    @DisplayName("Un 404 de cuentas-service no se disfraza de falla: la cuenta no existe o no es del cliente")
    void noEncontradaSePropaga() {
        assertThatThrownBy(() -> fallback(Errores.cuentaOrigenNoEncontrada())).extracting("codigo")
                .isEqualTo("CUENTA_ORIGEN_NO_ENCONTRADA");
    }

    @Test
    @DisplayName("Reglas de fraude: primero el monto, despues las cuentas en observacion")
    void reglas() {
        ReglasDeFraude reglas = new ReglasDeFraude(propiedades);
        LocalDateTime ahora = LocalDateTime.now();
        assertThat(reglas.evaluar(new Pago("1", Pago.TRANSFERENCIA, 4, 14, 101L, 102L, null, new BigDecimal("5000001"),
                "PREVIA", "WEB", "k1", ahora))).contains(ReglasDeFraude.MONTO_SOBRE_LIMITE);
        assertThat(reglas.evaluar(new Pago("2", Pago.DEPOSITO, 4, 14, null, 666L, null, BigDecimal.TEN, "NO_APLICA",
                "WEB", "k2", ahora))).contains(ReglasDeFraude.CUENTA_EN_OBSERVACION);
        assertThat(reglas.evaluar(new Pago("3", Pago.TRANSFERENCIA, 4, 14, 101L, 102L, null, BigDecimal.TEN, "PREVIA",
                "WEB", "k3", ahora))).isEqualTo(Optional.empty());
    }
}
