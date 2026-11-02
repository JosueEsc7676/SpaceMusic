package com.bpm.music.controllers;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service worker de la app. Guarda TODOS los archivos de la interfaz en el
 * dispositivo para que la app abra al instante. La versión cambia en cada
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
            archivos.add(ruta);
        }
        archivos.add("/");
        String lista = archivos.stream().distinct().sorted().map(a -> "'" + a + "'").collect(Collectors.joining(","));
        this.script = """
            // Generado por el servidor. Versión: %s
            const CACHE = '%s';
            const SHELL = [%s];

            self.addEventListener('install', (e) => {
              e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL.map((u) => new Request(u, { cache: 'reload' })))));
            });

            self.addEventListener('activate', (e) => {
              e.waitUntil(caches.keys()
                .then((ks) => Promise.all(ks.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
                .then(() => self.clients.claim()));
            });

            self.addEventListener('message', (e) => { if (e.data === 'activar') self.skipWaiting(); });

            self.addEventListener('fetch', (e) => {
              const req = e.request;
              const url = new URL(req.url);
              if (req.method !== 'GET' || url.origin !== location.origin || url.pathname.startsWith('/api/')) return;
              if (req.mode === 'navigate' && (url.pathname === '/' || url.pathname === '/index.html')) {
                e.respondWith(caches.match('/index.html').then((r) => r || fetch(req)));
                return;
              }
              // Archivos de la interfaz: primero la copia guardada (instantáneo)
              e.respondWith(caches.match(req, { ignoreSearch: true }).then((r) => r || fetch(req)));
            });
            """.formatted(version, version, lista);
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
