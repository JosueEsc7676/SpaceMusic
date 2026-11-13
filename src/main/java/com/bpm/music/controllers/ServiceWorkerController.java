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
import java.util.stream.Collectors;

/**
 * Service worker de la app (plantilla en resources/sw/sw.js). Guarda la interfaz,
 * los datos, las portadas y las canciones escuchadas para usar la app sin conexión. La versión es la huella
 * del contenido: solo cambia cuando cambia algún archivo (no en cada reinicio del servidor). Cada archivo lleva
 * su propia huella y, al instalar una versión nueva, el teléfono copia de la versión anterior lo que no cambió
 * y descarga solo lo nuevo (el conjunto se activa completo: nunca mezcla archivos viejos y nuevos).
 */
@RestController
public class ServiceWorkerController {

    private final String version;
    private final String script;

    private static String huella(byte[] datos) {
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(datos);
            return java.util.HexFormat.of().formatHex(h, 0, 6);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public ServiceWorkerController() throws IOException {
        java.util.Map<String, String> archivos = new java.util.TreeMap<>();
        var resolver = new PathMatchingResourcePatternResolver();
        for (Resource r : resolver.getResources("classpath:/static/**/*.*")) {
            String url = r.getURL().toString();
            int i = url.indexOf("/static/");
            if (i < 0) continue;
            String ruta = url.substring(i + 7);
            if (ruta.endsWith(".html") && !ruta.equals("/index.html")) continue; // páginas de acceso: siempre de la red
            if (ruta.startsWith("/app/") || ruta.startsWith("/sonidos/")) continue;   // instaladores y sonidos: no se precargan
            // La administración requiere red: no descargarla para cada oyente al instalar la app.
            if (java.util.Set.of("/js/views/admin.js", "/js/views/soporte.js",
                    "/js/views/reportes-admin.js", "/js/views/descargas-admin.js", "/js/views/descargas-buscar.js",
                    "/js/views/organizacion-admin.js").contains(ruta)) continue;
            try (var in = r.getInputStream()) { archivos.put(ruta, huella(in.readAllBytes())); }
        }
        if (archivos.containsKey("/index.html")) archivos.put("/", archivos.get("/index.html"));
        // La plantilla está en resources/sw/sw.js (fuera de /static para que no se publique tal cual)
        String plantilla = new String(new ClassPathResource("sw/sw.js").getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String lista = archivos.entrySet().stream().map(e -> "['" + e.getKey() + "','" + e.getValue() + "']").collect(Collectors.joining(","));
        this.version = "sm-" + huella((plantilla + lista).getBytes(StandardCharsets.UTF_8));
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
