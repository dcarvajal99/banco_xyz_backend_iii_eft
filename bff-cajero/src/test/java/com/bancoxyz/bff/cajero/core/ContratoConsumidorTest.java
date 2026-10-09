package com.bancoxyz.bff.cajero.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Prueba de CONSUMIDOR: los records que este BFF usa para leer al core se construyen desde los mismos archivos
 * que el core verifica como proveedor. Si un record espera una clave que el contrato no trae, falla aqui.
 */
class ContratoConsumidorTest {

    private static final Path CONTRATOS = Path.of("contratos", "core-api");
    private final ObjectMapper estricto = JsonMapper.builder().addModule(new JavaTimeModule())
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES).build();

    @Test
    @DisplayName("Los DTO del cajero se construyen desde los contratos del core")
    void leeLosContratos() throws Exception {
        TarjetaAutenticadaCore tarjeta = estricto.readValue(CONTRATOS.resolve("tarjeta-autenticada.json").toFile(),
                TarjetaAutenticadaCore.class);
        CuentaCore cuenta = estricto.readValue(CONTRATOS.resolve("cuenta.json").toFile(), CuentaCore.class);
        OperacionCore operacion = estricto.readValue(CONTRATOS.resolve("operacion-retiro.json").toFile(), OperacionCore.class);
        assertThat(tarjeta.cuentaId()).isEqualTo(101);
        assertThat(cuenta.saldoDisponible()).isEqualByComparingTo("12060");
        assertThat(operacion.saldoResultante()).isEqualByComparingTo("9060");
    }

    @Test
    @DisplayName("Control negativo: si el core dejara de mandar un campo, la lectura falla en vez de dejarlo en null")
    void detectaCampoFaltante() {
        assertThatThrownBy(() -> estricto.readValue("{\"tarjetaId\":1,\"cuentaId\":2,\"ultimos4\":\"1234\"}",
                TarjetaAutenticadaCore.class)).isInstanceOf(MismatchedInputException.class);
    }

    @Test
    @DisplayName("Los errores del contrato se leen con su codigo y sus intentos restantes")
    void leeErrores() throws Exception {
        ErrorDelCore pin = ErrorDelCore.desde(401, Files.readAllBytes(CONTRATOS.resolve("error-pin-incorrecto.json")), estricto);
        assertThat(pin.getCodigo()).isEqualTo("PIN_INCORRECTO");
        assertThat(pin.getIntentosRestantes()).isEqualTo(2);
        ErrorDelCore html = ErrorDelCore.desde(502, "<html>proxy</html>".getBytes(StandardCharsets.UTF_8), estricto);
        assertThat(html.getCodigo()).isNull();
        assertThat(ErrorDelCore.desde(500, new byte[0], estricto).getCodigo()).isNull();
    }
}
