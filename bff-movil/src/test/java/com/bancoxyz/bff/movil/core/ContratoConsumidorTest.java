package com.bancoxyz.bff.movil.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
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

/** Prueba de consumidor: los records del BFF movil se construyen desde los contratos del core. */
class ContratoConsumidorTest {

    private static final Path CONTRATOS = Path.of("contratos", "core-api");
    private final ObjectMapper estricto = JsonMapper.builder().addModule(new JavaTimeModule())
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES).build();

    @Test
    @DisplayName("Usuario autenticado, cuentas y pagina de movimientos se leen desde los contratos")
    void leeLosContratos() throws Exception {
        UsuarioAutenticadoCore usuario = estricto.readValue(CONTRATOS.resolve("usuario-autenticado.json").toFile(),
                UsuarioAutenticadoCore.class);
        List<CuentaCore> cuentas = estricto.readValue(CONTRATOS.resolve("cuentas.json").toFile(), new TypeReference<>() {
        });
        PaginaMovimientosCore pagina = estricto.readValue(CONTRATOS.resolve("pagina-movimientos.json").toFile(),
                PaginaMovimientosCore.class);
        assertThat(usuario.clienteId()).isEqualTo(6L);
        assertThat(cuentas).hasSize(2);
        assertThat(pagina.contenido().get(0).monto()).isNegative();
    }

    @Test
    @DisplayName("Control negativo y lectura de errores del core")
    void negativoYErrores() throws Exception {
        assertThatThrownBy(() -> estricto.readValue("{\"usuarioId\":1}", UsuarioAutenticadoCore.class))
                .isInstanceOf(MismatchedInputException.class);
        assertThat(ErrorDelCore.desde(404, Files.readAllBytes(CONTRATOS.resolve("error-cuenta-no-encontrada.json")), estricto)
                .getCodigo()).isEqualTo("CUENTA_NO_ENCONTRADA");
        assertThat(ErrorDelCore.desde(500, "<html/>".getBytes(StandardCharsets.UTF_8), estricto).getCodigo()).isNull();
        assertThat(ErrorDelCore.desde(500, new byte[0], estricto).getCodigo()).isNull();
    }
}
