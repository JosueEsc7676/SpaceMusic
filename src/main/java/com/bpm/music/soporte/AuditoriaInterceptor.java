package com.bpm.music.soporte;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Audita automáticamente toda acción administrativa (cualquier petición que no sea de lectura a /api/admin/**)
 * y limita la frecuencia de las operaciones sensibles (diagnósticos, mantenimiento, avisos masivos).
 */
@Configuration
public class AuditoriaInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    private final AuditoriaService auditoria;

    /** Operaciones sensibles → máximo de ejecuciones por minuto por administrador. */
    private static final Map<String, Integer> LIMITES = Map.of(
            "/api/admin/soporte/diagnosticos", 6,
            "/api/admin/soporte/biblioteca/revisar", 3,
            "/api/admin/soporte/mantenimiento", 10,
            "/api/admin/aviso", 3,
            "/api/admin/soporte/exportar", 6,
            "/api/admin/soporte/respaldos", 3,
            "/api/admin/descargas", 20,
            "/api/admin/organizacion/evaluar", 2,
            "/api/admin/organizacion/iniciar", 4);
    private final Map<String, ConcurrentLinkedDeque<Long>> usos = new ConcurrentHashMap<>();
    private static final Set<String> LECTURA = Set.of("GET", "HEAD", "OPTIONS");

    public AuditoriaInterceptor(AuditoriaService auditoria) {
        this.auditoria = auditoria;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/admin/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        String ruta = req.getRequestURI();
        if (LECTURA.contains(req.getMethod()) && !ruta.startsWith("/api/admin/soporte/exportar")) return true;
        for (var e : LIMITES.entrySet()) {
            if (!ruta.startsWith(e.getKey())) continue;
            String clave = e.getKey() + "|" + req.getAttribute("sm.uid");
            var cola = usos.computeIfAbsent(clave, k -> new ConcurrentLinkedDeque<>());
            long ahora = System.currentTimeMillis();
            while (!cola.isEmpty() && ahora - cola.peekFirst() > 60_000) cola.pollFirst();
            if (cola.size() >= e.getValue()) {
                auditoria.registrar(req.getMethod() + " " + Sanitizador.patronRuta(ruta), "Límite de frecuencia alcanzado", "DENEGADA");
                res.setStatus(429);
                res.setContentType("application/json");
                res.setCharacterEncoding("UTF-8");
                res.getWriter().write("{\"error\":\"Demasiadas veces seguidas. Espera un minuto e inténtalo de nuevo.\"}");
                return false;
            }
            cola.addLast(ahora);
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse res, Object handler, Exception ex) {
        if (LECTURA.contains(req.getMethod()) && !req.getRequestURI().startsWith("/api/admin/soporte/exportar")) return;
        if (res.getStatus() == 429) return; // ya auditado
        String detalle = (String) req.getAttribute("sm.auditoria");
        String accion = req.getMethod() + " " + Sanitizador.patronRuta(req.getRequestURI());
        String resultado = ex != null || res.getStatus() >= 400 ? (res.getStatus() == 403 ? "DENEGADA" : "ERROR") : "OK";
        auditoria.registrar(accion, (detalle != null ? detalle + " · " : "") + "HTTP " + res.getStatus(), resultado);
    }

    /** Los controladores pueden añadir un detalle legible (p. ej. "rol de @ana: USER → ADMIN"). */
    public static void detalle(HttpServletRequest req, String detalle) {
        if (req != null) req.setAttribute("sm.auditoria", detalle);
    }
}
