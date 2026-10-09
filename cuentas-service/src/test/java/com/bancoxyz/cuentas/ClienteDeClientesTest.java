package com.bancoxyz.cuentas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Optional;

import com.bancoxyz.cuentas.clientes.ClienteDeClientes;
import com.bancoxyz.cuentas.config.PropiedadesCuentas;
import com.bancoxyz.cuentas.cuenta.Cuenta;
import com.bancoxyz.cuentas.cuenta.RepositorioCuentas;
import com.bancoxyz.cuentas.error.ErrorDeNegocio;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/** Comportamiento alternativo cuando clientes-service no responde. */
class ClienteDeClientesTest {

    private final RepositorioCuentas cuentas = mock(RepositorioCuentas.class);
    private final ClienteDeClientes cliente = new ClienteDeClientes(RestClient.builder(),
            new PropiedadesCuentas(null, false, null, null, null, null, new PropiedadesCuentas.Clientes("https://clientes")),
            mock(OAuth2AuthorizedClientManager.class), cuentas);

    private ClienteDeClientes.Titular respaldo(long clienteId, Exception falla) throws Exception {
        Method metodo = ClienteDeClientes.class.getDeclaredMethod("titularConocido", long.class, Exception.class);
        metodo.setAccessible(true);
        try {
            return (ClienteDeClientes.Titular) metodo.invoke(cliente, clienteId, falla);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    @Test
    @DisplayName("Sin conexion y el cliente ya tiene cuentas: se usa el titular conocido, marcado como no verificado")
    void titularConocido() throws Exception {
        Cuenta existente = mock(Cuenta.class);
        when(existente.getTitular()).thenReturn("Jane Smith");
        when(cuentas.findFirstByClienteIdOrderByIdAsc(any())).thenReturn(Optional.of(existente));

        ClienteDeClientes.Titular titular = respaldo(5, new ResourceAccessException("sin conexion"));

        assertThat(titular).isEqualTo(new ClienteDeClientes.Titular("Jane Smith", false));
    }

    @Test
    @DisplayName("Sin conexion y cliente desconocido: 503 CLIENTES_NO_DISPONIBLE (no se abre a nombre de nadie)")
    void clienteDesconocido() {
        when(cuentas.findFirstByClienteIdOrderByIdAsc(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> respaldo(77, new ResourceAccessException("sin conexion")))
                .isInstanceOf(ErrorDeNegocio.class).extracting("codigo").isEqualTo("CLIENTES_NO_DISPONIBLE");
    }
}
