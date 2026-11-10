package com.bpm.music.soporte;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** Registro del filtro de monitorización (antes que la seguridad, para contar también los accesos denegados). */
@Configuration
public class SoporteConfig {

    @Bean
    public FilterRegistrationBean<MonitorFiltro> monitorRegistro(MonitorFiltro filtro) {
        FilterRegistrationBean<MonitorFiltro> r = new FilterRegistrationBean<>(filtro);
        r.setOrder(Ordered.HIGHEST_PRECEDENCE + 5);
        r.addUrlPatterns("/api/*", "/sw.js", "/", "/index.html");
        return r;
    }
}
