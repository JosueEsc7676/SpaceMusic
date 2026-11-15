package com.bpm.music.controllers;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Instaladores de iPhone. Rutas exactas: no expone otros archivos de app/ ni subdirectorios. */
@RestController
public class IosAppsController {
    private final Path carpeta;

    public IosAppsController(@Value("${music.apps-path:./app}") String carpeta) {
        this.carpeta = Path.of(carpeta).toAbsolutePath().normalize();
    }

    @GetMapping("/app/SpaceMusic.ipa")
    public ResponseEntity<Resource> ultima() { return archivo("SpaceMusic.ipa", "application/octet-stream", false, true); }

    @GetMapping("/app/SpaceMusic-{numero:[0-9]+}.ipa")
    public ResponseEntity<Resource> version(@PathVariable("numero") String numero) {
        if (!numero.matches("[0-9]{1,12}")) return ResponseEntity.notFound().build();
        return archivo("SpaceMusic-" + numero + ".ipa", "application/octet-stream", true, true);
    }

    @GetMapping("/app/ios.html")
    public ResponseEntity<Resource> pagina() { return archivo("ios.html", "text/html;charset=UTF-8", false, false); }

    @GetMapping("/app/ios-update.css")
    public ResponseEntity<Resource> estilo() { return archivo("ios-update.css", "text/css;charset=UTF-8", false, false); }

    @GetMapping("/app/ios-update.js")
    public ResponseEntity<Resource> script() { return archivo("ios-update.js", "text/javascript;charset=UTF-8", false, false); }

    private ResponseEntity<Resource> archivo(String nombre, String tipo, boolean fijo, boolean descarga) {
        Path p = carpeta.resolve(nombre).normalize();
        if (!p.getParent().equals(carpeta) || !Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
            return ResponseEntity.notFound().build();
        }
        var respuesta = ResponseEntity.ok().contentType(MediaType.parseMediaType(tipo))
                .cacheControl(fijo ? CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic() : CacheControl.noStore());
        if (descarga) respuesta.header("Content-Disposition", "attachment; filename=\"SpaceMusic.ipa\"");
        return respuesta.body(new FileSystemResource(p));
    }
}
