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

    private final com.bpm.music.services.GeneroService generos;

    public CancionController(BibliotecaService biblioteca, CancionService canciones, UsuarioService usuarios,
                             LetrasService letras, com.bpm.music.services.GeneroService generos,
                             @Value("${app.allow-downloads:true}") boolean permitirDescargas) {
        this.generos = generos;
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
    public ResponseEntity<Resource> audio(@PathVariable Long id, @RequestParam(required = false) String calidad) {
        // calidad=ligera: versión AAC 128 kbps para datos móviles (si todavía no está lista, el original)
        boolean pidioLigera = "ligera".equals(calidad) && ligero != null;
        File f = pidioLigera ? ligero.archivo(id) : canciones.archivo(id);
        if (f == null || !f.canRead()) return ResponseEntity.notFound().build();
        boolean ligera = pidioLigera && ligero.esLigera(f);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(mime(f.getName())))
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header("X-SM-Calidad", ligera ? "ligera" : "original")
                .cacheControl(cacheAudio(pidioLigera, ligera))
                // Validador fuerte: el navegador puede reutilizar y combinar los trozos (rangos) que ya tiene
                .eTag(etagAudio(f, ligera))
                .lastModified(f.lastModified())
                .body(new FileSystemResource(f));
    }

    /**
     * Pidió la versión ligera y todavía no está lista: se entrega el original, pero el navegador solo lo guarda
     * unos minutos bajo esta dirección (así, en cuanto la ligera esté preparada, la usa).
     */
    public static CacheControl cacheAudio(boolean pidioLigera, boolean ligera) {
        return pidioLigera && !ligera ? CacheControl.maxAge(10, TimeUnit.MINUTES).cachePrivate()
                : CacheControl.maxAge(7, TimeUnit.DAYS).cachePrivate();
    }

    public static String etagAudio(File f, boolean ligera) {
        return "\"" + Long.toHexString(f.length()) + "-" + Long.toHexString(f.lastModified()) + (ligera ? "-l" : "") + "\"";
    }

    private com.bpm.music.services.AudioLigeroService ligero;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setLigero(com.bpm.music.services.AudioLigeroService ligero) { this.ligero = ligero; }

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
        List<Map<String, Object>> r = canciones.subir(files);
        // Género al momento si el artista ya se conoce (para el resumen de la subida)
        generos.completarSubidas(r.stream().map(m -> m.get("cancion")).filter(CancionDTO.class::isInstance)
                .map(CancionDTO.class::cast).toList());
        return r;
    }

    /** Antes de subir: ¿cuáles de estos archivos (nombre y tamaño) ya están en el servidor? */
    @PostMapping("/subir/revisar")
    @PreAuthorize("hasAnyRole('ADMIN','COLABORADOR')")
    public List<Boolean> revisarSubida(@RequestBody List<Map<String, Object>> archivos) {
        if (archivos == null || archivos.size() > 500) throw new IllegalArgumentException("Demasiados archivos");
        return canciones.yaExisten(archivos);
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
