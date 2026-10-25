package com.bpm.music.controllers;

import com.bpm.music.dtos.AlbumDTO;
import com.bpm.music.dtos.CancionDTO;
import com.bpm.music.security.CurrentUser;
import com.bpm.music.services.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/canciones")
public class CancionController {

    private final BibliotecaService biblioteca;
    private final CancionService canciones;
    private final UsuarioService usuarios;
    private final LetrasService letras;
    private final boolean permitirDescargas;

    public CancionController(BibliotecaService biblioteca, CancionService canciones, UsuarioService usuarios,
                             LetrasService letras, @Value("${app.allow-downloads:true}") boolean permitirDescargas) {
        this.biblioteca = biblioteca;
        this.canciones = canciones;
        this.usuarios = usuarios;
        this.letras = letras;
        this.permitirDescargas = permitirDescargas;
    }

    @GetMapping
    public List<CancionDTO> todas() {
        return biblioteca.todas();
    }

    @GetMapping("/buscar")
    public List<CancionDTO> buscar(@RequestParam(defaultValue = "") String q,
                                   @RequestParam(defaultValue = "50") int limite) {
        return biblioteca.buscarCanciones(q, Math.min(Math.max(limite, 1), 200));
    }

    @GetMapping("/top")
    public List<CancionDTO> top(@RequestParam(defaultValue = "20") int limite) {
        return biblioteca.top(Math.min(limite, 100));
    }

    @GetMapping("/recientes")
    public List<CancionDTO> recientes(@RequestParam(defaultValue = "20") int limite) {
        return biblioteca.recienAgregadas(Math.min(limite, 100));
    }

    /** Favoritos del usuario actual. */
    @GetMapping("/favoritos")
    public List<CancionDTO> favoritos() {
        return usuarios.favoritos(CurrentUser.id());
    }

    @GetMapping("/artista/{artista}")
    public List<CancionDTO> deArtista(@PathVariable String artista) {
        return biblioteca.cancionesDeArtista(artista);
    }

    @GetMapping("/album/{album}")
    public List<CancionDTO> deAlbum(@PathVariable String album) {
        return biblioteca.album(album).map(AlbumDTO::canciones).orElse(List.of());
    }

    @GetMapping("/albumes")
    public List<AlbumDTO> albumes() {
        return biblioteca.albumes();
    }

    @GetMapping("/{id}")
    public CancionDTO porId(@PathVariable Long id) {
        return biblioteca.requerir(id);
    }

    @PutMapping("/{id}/favorito")
    public Map<String, Object> favorito(@PathVariable Long id) {
        return usuarios.toggleFavorito(CurrentUser.id(), id);
    }

    @PostMapping("/{id}/reproducir")
    public ResponseEntity<Void> reproducir(@PathVariable Long id,
                                           @RequestParam(defaultValue = "otro") String contexto,
                                           @RequestParam(required = false) Integer segundos) {
        usuarios.registrarReproduccion(CurrentUser.id(), id, contexto, segundos);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/letra")
    public Map<String, Object> letra(@PathVariable Long id) {
        return letras.obtener(id);
    }

    // ═══════════════════ Audio ═══════════════════

    /**
     * Streaming de audio con soporte de rangos (206 Partial Content):
     * Spring sirve solo el trozo pedido, así se puede adelantar la canción.
     */
    @GetMapping("/{id}/audio")
    public ResponseEntity<Resource> audio(@PathVariable Long id) {
        File f = canciones.archivo(id);
        if (f == null || !f.canRead()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(mime(f.getName())))
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .cacheControl(CacheControl.maxAge(7, TimeUnit.DAYS).cachePrivate())
                .lastModified(f.lastModified())
                .body(new FileSystemResource(f));
    }

    @GetMapping("/{id}/descargar")
    public ResponseEntity<Resource> descargar(@PathVariable Long id) {
        if (!permitirDescargas && !CurrentUser.esAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        CancionDTO c = biblioteca.requerir(id);
        File f = canciones.archivo(id);
        if (f == null || !f.canRead()) return ResponseEntity.notFound().build();
        String nombre = (c.getArtista() + " - " + c.getNombre()).replaceAll("[\\\\/:*?\"<>|]", "_")
                + "." + MetadataService.extension(f.getName());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(mime(f.getName())))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(nombre, StandardCharsets.UTF_8).build().toString())
                .contentLength(f.length())
                .body(new FileSystemResource(f));
    }

    // ═══════════════════ Edición (colaboradores) ═══════════════════

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','COLABORADOR')")
    public CancionDTO editar(@PathVariable Long id, @RequestBody Map<String, Object> datos) {
        return canciones.editar(id, datos);
    }

    /** Alias de la versión anterior. */
    @PutMapping("/{id}/editar")
    @PreAuthorize("hasAnyRole('ADMIN','COLABORADOR')")
    public CancionDTO editarAlias(@PathVariable Long id, @RequestBody Map<String, Object> datos) {
        return canciones.editar(id, datos);
    }

    @PostMapping("/{id}/portada")
    @PreAuthorize("hasAnyRole('ADMIN','COLABORADOR')")
    public CancionDTO portada(@PathVariable Long id, @RequestParam("file") MultipartFile file) throws IOException {
        return canciones.cambiarPortada(id, file);
    }

    @PostMapping("/subir")
    @PreAuthorize("hasAnyRole('ADMIN','COLABORADOR')")
    public List<Map<String, Object>> subir(@RequestParam("files") List<MultipartFile> files) {
        if (files == null || files.isEmpty()) throw new IllegalArgumentException("No se recibió ningún archivo");
        if (files.size() > 50) throw new IllegalArgumentException("Máximo 50 archivos a la vez");
        return canciones.subir(files);
    }

    static String mime(String nombre) {
        return switch (MetadataService.extension(nombre)) {
            case "flac" -> "audio/flac";
            case "m4a", "mp4", "aac" -> "audio/mp4";
            case "ogg", "oga" -> "audio/ogg";
            case "opus" -> "audio/ogg";
            case "wav" -> "audio/wav";
            default -> "audio/mpeg";
        };
    }
}
