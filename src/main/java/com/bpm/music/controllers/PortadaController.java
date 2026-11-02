package com.bpm.music.controllers;

import com.bpm.music.dtos.CancionDTO;
import com.bpm.music.repositories.CancionRepository;
import com.bpm.music.services.BibliotecaService;
import com.bpm.music.services.ImagenService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Portadas de canciones. Acepta ?s=64|160|300|640 para pedir una miniatura
 * (mucho más ligera en listas y en el móvil). Si la canción no tiene portada
 * se devuelve una imagen generada.
 */
@RestController
@RequestMapping("/api/portada")
public class PortadaController {

    private static final Set<Integer> TAMANOS = Set.of(64, 160, 300, 640);

    private final CancionRepository repo;
    private final BibliotecaService biblioteca;
    private final ImagenService imagenes;

    public PortadaController(CancionRepository repo, BibliotecaService biblioteca, ImagenService imagenes) {
        this.repo = repo;
        this.biblioteca = biblioteca;
        this.imagenes = imagenes;
    }

    @GetMapping("/db/{id}")
    public ResponseEntity<byte[]> portada(@PathVariable Long id, @RequestParam(required = false) Integer s,
                                          @RequestParam(required = false) String v) {
        Optional<CancionDTO> c = biblioteca.porId(id);
        // La URL lleva la huella de la imagen (?v=): si la miniatura ya está en disco
        // se sirve directamente, sin leer la imagen original de la base de datos.
        if (v != null && s != null && TAMANOS.contains(s) && v.matches("[0-9a-f]{6,16}")) {
            byte[] mini = imagenes.leerMiniatura(id, v, s);
            if (mini != null) {
                return ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_JPEG)
                        .eTag("\"" + id + "-" + v + "-" + s + "\"")
                        .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable())
                        .body(mini);
            }
        }
        byte[] datos = repo.findPortadaById(id).orElse(null);

        if (datos == null || datos.length == 0) {
            if (c.isPresent() && c.get().getPortadaUrl() != null && c.get().getPortadaUrl().startsWith("http")) {
                return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(c.get().getPortadaUrl())).build();
            }
            String texto = c.map(CancionDTO::getAlbum).filter(a -> !"Single".equals(a))
                    .or(() -> c.map(CancionDTO::getNombre)).orElse("");
            long semilla = c.map(x -> (long) BibliotecaService.normalizar(x.getAlbum() + x.getArtista()).hashCode()).orElse(id);
            return ResponseEntity.ok()
                    .contentType(MediaType.valueOf("image/svg+xml"))
                    .cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePublic())
                    .body(ImagenService.portadaGenerada(semilla, texto));
        }

        String tipo = ImagenService.tipoDeBytes(datos);
        if (s != null && TAMANOS.contains(s)) {
            byte[] mini = imagenes.miniaturaPortada(id, datos, s);
            if (mini != null) {
                datos = mini;
                tipo = "image/jpeg";
            }
        }
        String etag = "\"" + id + "-" + (s == null ? 0 : s) + "-" + datos.length + "\"";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(tipo))
                .eTag(etag)
                .cacheControl(v != null
                        ? CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable()
                        : CacheControl.maxAge(30, TimeUnit.DAYS).cachePublic())
                .body(datos);
    }

    /** Ruta antigua. */
    @GetMapping("/id/{id}")
    public ResponseEntity<byte[]> alias(@PathVariable Long id, @RequestParam(required = false) Integer s) {
        return portada(id, s, null);
    }
}
