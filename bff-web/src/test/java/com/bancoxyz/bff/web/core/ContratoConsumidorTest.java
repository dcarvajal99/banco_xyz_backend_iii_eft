package com.bancoxyz.bff.web.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Prueba de consumidor: la web lee nueve formas del core y todas se construyen desde los contratos. */
class ContratoConsumidorTest {

    private static final Path CONTRATOS = Path.of("contratos", "core-api");
    private final ObjectMapper estricto = JsonMapper.builder().addModule(new JavaTimeModule())
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES).build();

    @Test
    @DisplayName("Todos los contratos que usa el BFF web se leen sin campos faltantes")
    void leeLosContratos() throws Exception {
        assertThat(leer("usuario-autenticado.json", UsuarioAutenticadoCore.class).rol()).isEqualTo("CLIENTE");
        assertThat(leer("cuenta.json", CuentaCore.class).tipo()).isEqualTo("ahorro");
        assertThat(leer("pagina-movimientos.json", PaginaMovimientosCore.class).totalPaginas()).isEqualTo(3);
        assertThat(leer("calidad.json", CalidadCore.class).rechazados()).hasSize(1);
        assertThat(lista("cuentas.json", new TypeReference<List<CuentaCore>>() { })).hasSize(2);
        assertThat(lista("estados-anuales.json", new TypeReference<List<EstadoAnualCore>>() { })).hasSize(1);
        assertThat(lista("clientes.json", new TypeReference<List<ClienteDelBancoCore>>() { })).hasSize(1);
        assertThat(lista("resumen-diario.json", new TypeReference<List<ResumenDiarioCore>>() { })).hasSize(1);
        assertThat(lista("cierres-vigentes.json", new TypeReference<List<CierreCore>>() { })).hasSize(1);
    }

    @Test
    @DisplayName("Control negativo y errores del core")
    void negativoYErrores() throws Exception {
        assertThatThrownBy(() -> estricto.readValue("{\"conjunto\":\"INTERESES\"}", CierreCore.class))
                .isInstanceOf(MismatchedInputException.class);
        assertThat(ErrorDelCore.desde(401, Files.readAllBytes(CONTRATOS.resolve("error-pin-incorrecto.json")), estricto).getCodigo())
                .isEqualTo("PIN_INCORRECTO");
        assertThat(ErrorDelCore.desde(502, "x".getBytes(), estricto).getCodigo()).isNull();
    }

    private <T> T leer(String archivo, Class<T> tipo) throws Exception {
        return estricto.readValue(CONTRATOS.resolve(archivo).toFile(), tipo);
    }

    private <T> T lista(String archivo, TypeReference<T> tipo) throws Exception {
        return estricto.readValue(CONTRATOS.resolve(archivo).toFile(), tipo);
    }
}
