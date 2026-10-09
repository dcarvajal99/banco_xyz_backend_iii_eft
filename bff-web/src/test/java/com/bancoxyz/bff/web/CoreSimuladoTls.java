package com.bancoxyz.bff.web;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;

/**
 * Core simulado por HTTPS que EXIGE certificado de cliente (need) y responde con los contratos de contratos/core-api.
 *
 * <p>Registra el sujeto del certificado con que llego cada llamada: con eso las pruebas comprueban que el BFF entra al
 * core con su propio certificado, tal como lo pide el core real.</p>
 */
final class CoreSimuladoTls {

    private final HttpsServer servidor;
    private final ExecutorService hilos = Executors.newFixedThreadPool(8);
    private final Map<String, String> contratosPorRuta;
    private final List<String> certificadosVistos = new CopyOnWriteArrayList<>();

    private CoreSimuladoTls(Map<String, String> contratosPorRuta) throws IOException {
        this.contratosPorRuta = contratosPorRuta;
        SSLContext contexto = PkiDePrueba.contexto("banco-core-api");
        servidor = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        servidor.setHttpsConfigurator(new HttpsConfigurator(contexto) {
            @Override
            public void configure(HttpsParameters parametros) {
                SSLParameters ssl = contexto.getDefaultSSLParameters();
                ssl.setNeedClientAuth(true);
                parametros.setSSLParameters(ssl);
            }
        });
        servidor.createContext("/api/v1/", this::responder);
        servidor.setExecutor(hilos);
        servidor.start();
    }

    /** @param contratosPorRuta expresion regular de la ruta -> archivo de contratos/core-api */
    static CoreSimuladoTls iniciar(Map<String, String> contratosPorRuta) {
        try {
            return new CoreSimuladoTls(contratosPorRuta);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo iniciar el core simulado", e);
        }
    }

    String url() {
        return "https://localhost:" + servidor.getAddress().getPort() + "/api/v1";
    }

    List<String> certificadosVistos() {
        return certificadosVistos;
    }

    void detener() {
        servidor.stop(0);
        hilos.shutdownNow();
    }

    private void responder(HttpExchange intercambio) throws IOException {
        try {
            certificadosVistos.add(((HttpsExchange) intercambio).getSSLSession().getPeerPrincipal().getName());
            intercambio.getRequestBody().readAllBytes();
            String ruta = intercambio.getRequestURI().getPath();
            String contrato = contratosPorRuta.entrySet().stream().filter(e -> ruta.matches(e.getKey()))
                    .map(Map.Entry::getValue).findFirst().orElse(null);
            byte[] cuerpo = contrato == null ? "{}".getBytes(StandardCharsets.UTF_8)
                    : Files.readAllBytes(Path.of("contratos", "core-api", contrato));
            intercambio.getResponseHeaders().set("Content-Type", "application/json");
            intercambio.sendResponseHeaders(contrato == null ? 404 : 200, cuerpo.length);
            intercambio.getResponseBody().write(cuerpo);
        } finally {
            intercambio.close();
        }
    }
}
