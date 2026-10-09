package com.bancoxyz.bff.cajero.seguridad;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

/**
 * Re-expone un cuerpo ya leido. El filtro de firma necesita los bytes crudos antes que el controlador,
 * y el stream de una solicitud solo se puede leer una vez.
 *
 * <p>No se usa {@code ContentCachingRequestWrapper}: ese solo guarda lo que otro ya leyo, asi que el
 * filtro no tendria el cuerpo en el momento de verificar la firma.</p>
 */
class SolicitudConCuerpo extends HttpServletRequestWrapper {

    private final byte[] cuerpo;

    SolicitudConCuerpo(HttpServletRequest request, byte[] cuerpo) {
        super(request);
        this.cuerpo = cuerpo;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream entrada = new ByteArrayInputStream(cuerpo);
        return new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return entrada.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException("Lectura asincrona no soportada");
            }

            @Override
            public int read() {
                return entrada.read();
            }

            @Override
            public int read(byte[] destino, int desde, int largo) {
                return entrada.read(destino, desde, largo);
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        String codificacion = getCharacterEncoding();
        Charset juego = codificacion == null ? StandardCharsets.UTF_8 : Charset.forName(codificacion);
        return new BufferedReader(new InputStreamReader(getInputStream(), juego));
    }

    @Override
    public int getContentLength() {
        return cuerpo.length;
    }

    @Override
    public long getContentLengthLong() {
        return cuerpo.length;
    }
}
