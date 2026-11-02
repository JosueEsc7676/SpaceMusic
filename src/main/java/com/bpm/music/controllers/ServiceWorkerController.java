package com.bpm.music.controllers;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service worker de la app (plantilla en resources/sw/sw.js). Guarda la interfaz,
 * los datos, las portadas y las canciones escuchadas para usar la app sin conexión. La versión cambia en cada
 * arranque del servidor: cuando hay una versión nueva, el navegador descarga
 * el conjunto completo de una vez (nunca mezcla archivos viejos y nuevos).
 */
@RestController
public class ServiceWorkerController {

    private final String version = "sm-" + Long.toString(System.currentTimeMillis(), 36);
    private final String script;

    public ServiceWorkerController() throws IOException {
        List<String> archivos = new ArrayList<>();
        var resolver = new PathMatchingResourcePatternResolver();
        for (Resource r : resolver.getResources("classpath:/static/**/*.*")) {
            String url = r.getURL().toString();
            int i = url.indexOf("/static/");
            if (i < 0) continue;
            String ruta = url.substring(i + 7);
            if (ruta.endsWith(".html") && !ruta.equals("/index.html")) continue; // páginas de acceso: siempre de la red
            if (ruta.startsWith("/app/") || ruta.startsWith("/sonidos/")) continue;   // instaladores y sonidos: no se precargan
            archivos.add(ruta);
        }
        archivos.add("/");
        String lista = archivos.stream().distinct().sorted().map(a -> "'" + a + "'").collect(Collectors.joining(","));
        // La plantilla está en resources/sw/sw.js (fuera de /static para que no se publique tal cual)
        String plantilla = new String(new ClassPathResource("sw/sw.js").getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        this.script = plantilla.replace("__VERSION__", version).replace("__SHELL__", lista);
    }

    @GetMapping(value = "/sw.js", produces = "application/javascript")
    public ResponseEntity<String> sw() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .contentType(MediaType.parseMediaType("application/javascript; charset=UTF-8"))
                .header("Service-Worker-Allowed", "/")
                .body(script);
    }
}
