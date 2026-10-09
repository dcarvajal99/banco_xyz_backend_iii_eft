package com.bancoxyz.cuentas;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Token del usuario reenviado por el BFF, token de servicio y certificado del llamante. */
class ConsultasYSeguridadTest extends PruebaDeCuentas {

    @Test
    @DisplayName("Un cliente ve sus cuentas; la de otro cliente responde 404 igual que una inexistente")
    void clienteVeSoloLoSuyo() throws Exception {
        mvc.perform(get("/api/v1/clientes/" + DIANA + "/cuentas").with(cliente("diana.prince", DIANA, "bff-web", "cuentas.leer")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].estado").value("ACTIVA")).andExpect(jsonPath("$[0].origen").value("MIGRACION"));
        mvc.perform(get("/api/v1/cuentas/102").with(cliente("diana.prince", DIANA, "bff-movil", "cuentas.leer")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.codigo").value("CUENTA_NO_ENCONTRADA"));
        mvc.perform(get("/api/v1/clientes/" + JANE + "/cuentas").with(cliente("diana.prince", DIANA, "bff-web", "cuentas.leer")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.codigo").value("CLIENTE_NO_ENCONTRADO"));
    }

    @Test
    @DisplayName("El ejecutivo y pagos-service (token de servicio) ven cualquier cuenta")
    void ejecutivoYServicio() throws Exception {
        mvc.perform(get("/api/v1/cuentas/102").with(ejecutivo("cuentas.leer"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.titular").value("Jane Smith"));
        mvc.perform(get("/api/v1/cuentas/104").with(servicio("pagos-service", "cuentas.leer"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.clienteId").doesNotExist());
    }

    @Test
    @DisplayName("Movimientos: los del cierre publicado (nunca los de la corrida fallida), paginados")
    void movimientos() throws Exception {
        mvc.perform(get("/api/v1/cuentas/101/movimientos?tamano=2").with(cliente("diana.prince", DIANA, "bff-web", "cuentas.leer")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElementos").value(3))
                .andExpect(jsonPath("$.totalPaginas").value(2)).andExpect(jsonPath("$.contenido[0].id").value("C-1"));
        mvc.perform(get("/api/v1/cuentas/101/estados-anuales").with(cliente("diana.prince", DIANA, "bff-web", "cuentas.leer")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].saldoNeto").value(1400.0));
    }

    @Test
    @DisplayName("Token de usuario presentado por quien no es un BFF autorizado: 403 CERTIFICADO_NO_CORRESPONDE")
    void tokenDeUsuarioFueraDelBff() throws Exception {
        mvc.perform(get("/api/v1/cuentas/101").with(cliente("diana.prince", DIANA, "pagos-service", "cuentas.leer")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.codigo").value("CERTIFICADO_NO_CORRESPONDE"));
    }

    @Test
    @DisplayName("Token de servicio con el certificado de otro servicio: 403; sin certificado: 401 CERTIFICADO_REQUERIDO")
    void certificadoDelServicio() throws Exception {
        mvc.perform(get("/api/v1/cuentas/101").with(conCertificado(token("pagos-service", null, null, null, "cuentas.leer"), "bff-cajero")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.codigo").value("CERTIFICADO_NO_CORRESPONDE"));
        mvc.perform(get("/api/v1/cuentas/101").with(token("pagos-service", null, null, null, "cuentas.leer")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.codigo").value("CERTIFICADO_REQUERIDO"));
    }

    @Test
    @DisplayName("Sin el scope de la operacion: 403; sin token: 401; reportes solo para el ejecutivo")
    void scopesYRoles() throws Exception {
        mvc.perform(get("/api/v1/cuentas/101/movimientos").with(cliente("diana.prince", DIANA, "bff-web", "pagos.leer")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.codigo").value("ACCESO_DENEGADO"));
        mvc.perform(get("/api/v1/cuentas/101")).andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"));
        mvc.perform(get("/api/v1/reportes/calidad").with(cliente("diana.prince", DIANA, "bff-web", "reportes.leer")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/reportes/calidad").with(ejecutivo("reportes.leer"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.rechazados.length()").value(3));
        mvc.perform(get("/api/v1/reportes/resumen-diario").with(ejecutivo("reportes.leer"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    @DisplayName("Errores con el mismo ProblemDetail: parametro invalido 400 y ruta inexistente 404")
    void errores() throws Exception {
        mvc.perform(get("/api/v1/cuentas/abc").with(ejecutivo("cuentas.leer"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:bancoxyz:problema:solicitud-invalida"))
                .andExpect(jsonPath("$.correlacionId").isNotEmpty());
        mvc.perform(get("/api/v1/cuentas/101/movimientos?tamano=500").with(ejecutivo("cuentas.leer")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"));
        mvc.perform(get("/api/v1/cuentas/999").with(jwt())).andExpect(status().isUnauthorized());
    }
}
