package com.bpm.music.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

/**
 * ETag en las respuestas JSON de lectura más usadas: si los datos no cambiaron,
 * el navegador recibe un 304 vacío en lugar de todo el contenido otra vez.
 * No se aplica al audio ni a las portadas (tienen su propia caché y se transmiten por partes).
 */
@Configuration
public class RendimientoConfig {

    @Bean
    public FilterRegistrationBean<ShallowEtagHeaderFilter> etagJson() {
        FilterRegistrationBean<ShallowEtagHeaderFilter> r = new FilterRegistrationBean<>(new ShallowEtagHeaderFilter());
        r.addUrlPatterns(
                "/api/inicio", "/api/buscar", "/api/albumes", "/api/albumes/*",
                "/api/artistas", "/api/artistas/*", "/api/generos", "/api/generos/*",
                "/api/mixes", "/api/mixes/*", "/api/letras/*", "/api/canciones/recientes",
                "/api/canciones/top", "/api/playlists", "/api/playlists/*", "/api/biblioteca/*");
        r.setName("etagJson");
        r.setOrder(10);
        return r;
    }
}
