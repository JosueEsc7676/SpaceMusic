package com.bpm.music.controllers;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Instaladores de las apps (/app/SpaceMusic.apk, /app/version.json, /app/novedades.json…).
 * Primero se buscan en una carpeta del disco (music.apps-path): así una APK nueva se publica copiando el archivo,
 * sin reiniciar el servidor, y se entrega desde el disco (desde dentro del jar fallaba a veces: "ZipFile closed").
 * Si está en los dos lados, se entrega la más reciente (así un jar nuevo no queda tapado por una copia vieja del disco).
 */
@RestController
public class AppsController {

    private final Path carpeta;

    public AppsController(@Value("${music.apps-path:./app}") String carpeta) {
        this.carpeta = Path.of(carpeta).toAbsolutePath().normalize();
        copiarDelJar();
    }

    /** Al arrancar, los instaladores del jar que sean más nuevos que los del disco se copian a la carpeta. */
    private void copiarDelJar() {
        try {
            Files.createDirectories(carpeta);
            for (Resource r : new org.springframework.core.io.support.PathMatchingResourcePatternResolver().getResources("classpath:/static/app/*")) {
                String nombre = r.getFilename();
                if (nombre == null || !nombre.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,80}[.](?:apk|json)")) continue;
                Path destino = carpeta.resolve(nombre);
                if (Files.isRegularFile(destino) && Files.getLastModifiedTime(destino).toMillis() >= modificado(r)) continue;
                Path temporal = carpeta.resolve(nombre + ".tmp");
                try (var in = r.getInputStream()) { Files.copy(in, temporal, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
                Files.move(temporal, destino, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                Files.setLastModifiedTime(destino, java.nio.file.attribute.FileTime.fromMillis(Math.max(modificado(r), 1)));
            }
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(AppsController.class).warn("No se pudieron copiar los instaladores a {}: {}", carpeta, e.getMessage());
        }
    }

    private static long modificado(Resource r) {
        try { return r.lastModified(); } catch (java.io.IOException e) { return 0; }
    }

    @GetMapping("/app/{nombre:[A-Za-z0-9][A-Za-z0-9._-]{0,80}\\.(?:apk|json)}")
    public ResponseEntity<Resource> archivo(@PathVariable String nombre) {
        Path enDisco = carpeta.resolve(nombre).normalize();
        Resource jar = new ClassPathResource("static/app/" + nombre);
        Resource r = jar;
        if (enDisco.startsWith(carpeta) && Files.isRegularFile(enDisco)) {
            Resource disco = new FileSystemResource(enDisco);
            if (!jar.exists() || modificado(disco) >= modificado(jar)) r = disco;
        }
        if (!r.exists()) return ResponseEntity.notFound().build();
        boolean apk = nombre.endsWith(".apk");
        return ResponseEntity.ok()
                // version.json y novedades se consultan para saber si hay algo nuevo: nunca de una copia vieja
                .cacheControl(apk ? CacheControl.maxAge(10, TimeUnit.MINUTES) : CacheControl.noCache())
                .contentType(apk ? MediaType.parseMediaType("application/vnd.android.package-archive") : MediaType.APPLICATION_JSON)
                .body(r);
    }
}
