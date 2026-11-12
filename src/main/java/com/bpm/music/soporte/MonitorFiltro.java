package com.bpm.music.soporte;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.UUID;

/**
 * Mide cada petición (antes de la seguridad, para contar también los 401/403):
 * identificador de petición (X-Request-Id y en los registros), tiempo de respuesta, errores por ruta,
 * fallos de reproducción y actividad de la cuenta. No lee cuerpos ni guarda contenido.
 */
@Component
public class MonitorFiltro extends OncePerRequestFilter {

    private final MonitorService monitor;
    private final SesionesService sesiones;

    public MonitorFiltro(MonitorService monitor, SesionesService sesiones) {
        this.monitor = monitor;
        this.sesiones = sesiones;
    }


    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String rid = UUID.randomUUID().toString().substring(0, 8);
        long t0 = System.nanoTime();
        MDC.put("rid", rid);
        MDC.put("ruta", Sanitizador.patronRuta(req.getRequestURI()));
        res.setHeader("X-Request-Id", rid);
        TiemposBD.iniciar();
        // Server-Timing: tiempo de la aplicación y de la base de datos hasta que empieza la respuesta
        // (así el navegador separa red, servidor y base de datos)
        HttpServletResponse medida = new jakarta.servlet.http.HttpServletResponseWrapper(res) {
            private boolean puesta;
            private void poner() {
                if (puesta || isCommitted()) return;
                puesta = true;
                long[] db = TiemposBD.parcial();
                setHeader("Server-Timing", String.format(java.util.Locale.ROOT, "app;dur=%.1f, db;dur=%.1f;desc=\"%d consultas\"",
                        (System.nanoTime() - t0) / 1e6, db[0] / 1e6, db[1]));
            }
            @Override public jakarta.servlet.ServletOutputStream getOutputStream() throws IOException { poner(); return super.getOutputStream(); }
            @Override public java.io.PrintWriter getWriter() throws IOException { poner(); return super.getWriter(); }
            @Override public void flushBuffer() throws IOException { poner(); super.flushBuffer(); }
            @Override public void sendError(int sc) throws IOException { poner(); super.sendError(sc); }
            @Override public void sendError(int sc, String msg) throws IOException { poner(); super.sendError(sc, msg); }
            @Override public void setStatus(int sc) { super.setStatus(sc); if (sc == 204 || sc == 304) poner(); }
        };
        boolean esAudio = req.getRequestURI().endsWith("/audio");
        if (esAudio) monitor.audioEmpieza();
        try {
            chain.doFilter(req, medida);
        } finally {
            if (esAudio) monitor.audioTermina();
            long[] db = TiemposBD.terminar();
            try {
                String uri = req.getRequestURI();
                Object patron = req.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                String endpoint = patron != null ? patron.toString() : Sanitizador.patronRuta(uri);
                int estado = res.getStatus();
                boolean largo = req.isAsyncStarted() || uri.endsWith("/audio") || uri.endsWith("/descargar") || uri.equals("/api/eventos");
                double ms = (System.nanoTime() - t0) / 1e6;
                monitor.registrarPeticion(endpoint, estado, largo ? -1 : ms, uri.endsWith("/audio"));
                if (!largo) monitor.registrarTiempos(endpoint, ms, db[0] / 1e6, (int) db[1]);
                Long uid = req.getAttribute("sm.uid") instanceof Long l ? l : null;
                String ua = req.getHeader("User-Agent");
                if (uid != null) sesiones.registrar(uid, ua, version(ua), req.getRemoteAddr());
                if (estado == 401 || estado == 403) sesiones.falloAcceso(estado, endpoint, ua, req.getRemoteAddr(), uid);
            } catch (Exception ignored) {
                // El monitor nunca debe afectar a la petición
            }
            MDC.remove("rid");
            MDC.remove("ruta");
        }
    }

    private static String version(String ua) {
        if (ua == null) return null;
        int i = ua.indexOf("SpaceMusicApp/");
        if (i < 0) return null;
        String v = ua.substring(i + 14).split("[\\s;)]")[0];
        return v.length() > 12 ? v.substring(0, 12) : v;
    }
}
