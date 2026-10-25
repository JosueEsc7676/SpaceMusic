package com.bpm.music.controllers;

import com.bpm.music.dtos.PlaylistDTO;
import com.bpm.music.security.CurrentUser;
import com.bpm.music.services.ImagenService;
import com.bpm.music.services.PlaylistService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/playlists")
public class PlaylistController {

    private final PlaylistService service;
    private final ImagenService imagenes;

    public PlaylistController(PlaylistService service, ImagenService imagenes) {
        this.service = service;
        this.imagenes = imagenes;
    }

    @GetMapping
    public List<PlaylistDTO> mias() {
        return service.mias(CurrentUser.id());
    }

    @GetMapping("/publicas")
    public List<PlaylistDTO> publicas() {
        return service.publicas(CurrentUser.id());
    }

    @GetMapping("/{id}")
    public PlaylistDTO detalle(@PathVariable Long id) {
        return service.detalle(id, CurrentUser.id());
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public PlaylistDTO crear(@RequestBody Map<String, Object> b) throws IOException {
        PlaylistDTO p = service.crear(CurrentUser.id(), Objects.toString(b.get("nombre"), null),
                Objects.toString(b.get("descripcion"), null), Boolean.valueOf(Objects.toString(b.get("publica"), "false")), null);
        Object ids = b.get("canciones");
        if (ids instanceof List<?> lista && !lista.isEmpty()) {
            service.agregar(p.getId(), CurrentUser.id(), lista.stream().map(o -> Long.valueOf(o.toString())).toList());
            return service.detalle(p.getId(), CurrentUser.id());
        }
        return p;
    }

    @PostMapping("/con-imagen")
    public PlaylistDTO crearConImagen(@RequestParam("nombre") String nombre,
                                      @RequestParam(value = "descripcion", required = false) String descripcion,
                                      @RequestParam(value = "publica", required = false) Boolean publica,
                                      @RequestParam(value = "file", required = false) MultipartFile file) throws IOException {
        return service.crear(CurrentUser.id(), nombre, descripcion, publica, file);
    }

    @PutMapping("/{id}")
    public PlaylistDTO editar(@PathVariable Long id, @RequestBody Map<String, Object> b) {
        return service.editar(id, CurrentUser.id(), b);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> eliminar(@PathVariable Long id) {
        service.eliminar(id, CurrentUser.id());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/duplicar")
    public PlaylistDTO duplicar(@PathVariable Long id) {
        return service.duplicar(id, CurrentUser.id());
    }

    // ── Canciones ──

    @PostMapping("/{id}/canciones/{cancionId}")
    public Map<String, Object> agregar(@PathVariable Long id, @PathVariable Long cancionId) {
        return service.agregar(id, CurrentUser.id(), List.of(cancionId));
    }

    /** Agrega varias: {"ids": [1,2,3]} */
    @PostMapping("/{id}/canciones")
    public Map<String, Object> agregarVarias(@PathVariable Long id, @RequestBody Map<String, List<Long>> b) {
        return service.agregar(id, CurrentUser.id(), b.getOrDefault("ids", List.of()));
    }

    @DeleteMapping("/{id}/canciones/{cancionId}")
    public PlaylistDTO quitar(@PathVariable Long id, @PathVariable Long cancionId) {
        return service.quitar(id, CurrentUser.id(), cancionId);
    }

    /** Nuevo orden: {"ids": [3,1,2]} */
    @PutMapping("/{id}/orden")
    public PlaylistDTO reordenar(@PathVariable Long id, @RequestBody Map<String, List<Long>> b) {
        return service.reordenar(id, CurrentUser.id(), b.getOrDefault("ids", List.of()));
    }

    // ── Portada ──

    @PostMapping("/{id}/imagen")
    public PlaylistDTO subirImagen(@PathVariable Long id, @RequestParam("file") MultipartFile file) throws IOException {
        return service.cambiarImagen(id, CurrentUser.id(), file);
    }

    @PutMapping("/{id}/imagen")
    public PlaylistDTO cambiarImagen(@PathVariable Long id, @RequestParam("file") MultipartFile file) throws IOException {
        return service.cambiarImagen(id, CurrentUser.id(), file);
    }

    @DeleteMapping("/{id}/imagen")
    public PlaylistDTO quitarImagen(@PathVariable Long id) {
        return service.quitarImagen(id, CurrentUser.id());
    }

    @GetMapping("/imagen/{archivo}")
    public ResponseEntity<Resource> imagen(@PathVariable String archivo) {
        Path p = imagenes.resolver(null, archivo);
        if (p == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(ImagenService.tipoContenido(archivo)))
                .cacheControl(CacheControl.maxAge(30, TimeUnit.DAYS).cachePublic())
                .body(new FileSystemResource(p));
    }
}
