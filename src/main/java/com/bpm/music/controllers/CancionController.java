package com.bpm.music.controllers;

import com.bpm.music.dtos.CancionDTO;
import com.bpm.music.models.Cancion;
import com.bpm.music.services.CancionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/canciones")
@CrossOrigin(origins = "*")
public class CancionController {

    private final CancionService service;

    @Value("${music.path}")
    private String rutaMusica;

    // Chunk máximo por request: 2 MB — ideal para streaming fluido
    private static final long CHUNK_SIZE = 2 * 1024 * 1024;

    public CancionController(CancionService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<CancionDTO>> listarTodos() {
        return ResponseEntity.ok(service.listarTodos());
    }

    @GetMapping("/buscar")
    public ResponseEntity<List<CancionDTO>> buscar(@RequestParam String q) {
        return ResponseEntity.ok(service.buscarProfesional(q));
    }

    @GetMapping("/artista/{artista}")
    public ResponseEntity<List<CancionDTO>> getByArtista(@PathVariable String artista) {
        return ResponseEntity.ok(service.getByArtista(artista));
    }

    @GetMapping("/album/{album}")
    public ResponseEntity<List<CancionDTO>> getByAlbum(@PathVariable String album) {
        return ResponseEntity.ok(service.getByAlbum(album));
    }

    @GetMapping("/albumes")
    public ResponseEntity<List<Map<String, Object>>> getAlbumes() {
        return ResponseEntity.ok(service.getAlbumesConInfo());
    }

    @GetMapping("/favoritos")
    public ResponseEntity<List<CancionDTO>> getFavoritos() {
        return ResponseEntity.ok(service.getFavoritos());
    }

    @GetMapping("/top")
    public ResponseEntity<List<CancionDTO>> getTop() {
        return ResponseEntity.ok(service.getTopReproducidas());
    }

    @PutMapping("/{id}/favorito")
    public ResponseEntity<CancionDTO> toggleFavorito(@PathVariable Long id) {
        return ResponseEntity.ok(service.toggleFavorito(id));
    }

    @PostMapping("/{id}/reproducir")
    public ResponseEntity<Void> incrementarReproduccion(@PathVariable Long id) {
        service.incrementarReproduccion(id);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{id}")
    public ResponseEntity<CancionDTO> getById(@PathVariable Long id) {
        return ResponseEntity.ok(service.getById(id));
    }

    @GetMapping("/historial")
    public List<String> historial() {
        return service.obtenerHistorial();
    }

    // =============================================
    // STREAMING DE AUDIO — MP3 y FLAC
    // ResourceRegion hace streaming real sin cargar el archivo en memoria
    // =============================================
    @GetMapping("/{id}/audio")
    public ResponseEntity<ResourceRegion> servirAudio(
            @PathVariable Long id,
            @RequestHeader HttpHeaders headers) {

        try {
            Cancion cancion = service.getCancionEntity(id);
            File archivo = new File(rutaMusica, cancion.getRutaArchivo());

            // Si no existe en la ruta directa, buscar recursivamente en subcarpetas
            if (!archivo.exists()) {
                String soloNombre = new File(cancion.getRutaArchivo()).getName();
                File encontrado = buscarRecursivo(new File(rutaMusica), soloNombre);
                if (encontrado != null) {
                    archivo = encontrado;
                } else {
                    System.err.println("❌ No encontrado: " + cancion.getRutaArchivo());
                    return ResponseEntity.notFound().build();
                }
            }

            if (!archivo.canRead()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }

            Resource  resource   = new FileSystemResource(archivo);
            long      fileLength = resource.contentLength();
            MediaType mimeType   = MediaType.parseMediaType(
                    detectarMimeType(cancion.getRutaArchivo()));

            List<HttpRange> ranges = headers.getRange();

            if (!ranges.isEmpty()) {
                // Respuesta parcial 206 con el chunk pedido
                HttpRange range        = ranges.get(0);
                long      start        = range.getRangeStart(fileLength);
                long      end          = range.getRangeEnd(fileLength);
                long      regionLength = Math.min(CHUNK_SIZE, end - start + 1);

                ResourceRegion region = new ResourceRegion(resource, start, regionLength);
                return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                        .contentType(mimeType)
                        .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                        .body(region);
            }

            // Sin Range: entregar primer chunk (206 con Content-Range)
            long regionLength = Math.min(CHUNK_SIZE, fileLength);
            ResourceRegion region = new ResourceRegion(resource, 0, regionLength);
            return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                    .contentType(mimeType)
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .header(HttpHeaders.CONTENT_RANGE,
                            "bytes 0-" + (regionLength - 1) + "/" + fileLength)
                    .body(region);

        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/{id}/letra")
    public ResponseEntity<Map<String, Object>> getLetra(@PathVariable Long id) {
        try {
            Cancion cancion = service.getCancionEntity(id);
            Map<String, Object> response = new HashMap<>();
            response.put("letra",
                    cancion.getLetra() != null ? cancion.getLetra() : "");
            response.put("letraSincronizada",
                    cancion.getLetraSincronizada() != null ? cancion.getLetraSincronizada() : "");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("letra", "");
            response.put("letraSincronizada", "");
            return ResponseEntity.ok(response);
        }
    }

    private File buscarRecursivo(File dir, String nombre) {
        if (!dir.isDirectory()) return null;
        File[] archivos = dir.listFiles();
        if (archivos == null) return null;
        for (File f : archivos) {
            if (f.isFile() && f.getName().equals(nombre)) return f;
            if (f.isDirectory()) {
                File found = buscarRecursivo(f, nombre);
                if (found != null) return found;
            }
        }
        return null;
    }

    private String detectarMimeType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".flac")) return "audio/flac";
        if (lower.endsWith(".mp3"))  return "audio/mpeg";
        if (lower.endsWith(".ogg"))  return "audio/ogg";
        if (lower.endsWith(".wav"))  return "audio/wav";
        if (lower.endsWith(".m4a"))  return "audio/mp4";
        if (lower.endsWith(".aac"))  return "audio/aac";
        return "audio/mpeg";
    }
}