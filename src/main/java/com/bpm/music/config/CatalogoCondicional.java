package com.bpm.music.config;

import com.bpm.music.services.BibliotecaService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.context.request.ServletWebRequest;

/** Solo catálogo común autenticado. Las respuestas personales conservan su ETag por contenido. */
@Configuration
public class CatalogoCondicional implements WebMvcConfigurer {
    private final BibliotecaService biblioteca;
    public CatalogoCondicional(BibliotecaService biblioteca) { this.biblioteca = biblioteca; }
    @Override public void addInterceptors(InterceptorRegistry registro) {
        registro.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
                if (!"GET".equals(req.getMethod())) return true;
                // Carga inicial terminada antes de emitir el validador; nunca valida un catálogo inexistente.
                biblioteca.total();
                String variante = req.getRequestURI() + "?" + java.util.Objects.toString(req.getQueryString(), "");
                String etag = "\"catalogo-" + biblioteca.revision() + "-" + com.bpm.music.services.SincroniaLetra.revision(variante) + "\"";
                res.setHeader("Cache-Control", "private, no-cache");
                return !new ServletWebRequest(req, res).checkNotModified(etag);
            }
        }).addPathPatterns("/api/albumes", "/api/artistas", "/api/canciones");
    }
}
