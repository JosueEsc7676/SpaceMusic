package com.bpm.music.controllers;

import com.bpm.music.security.CurrentUser;
import com.bpm.music.services.ImagenService;
import com.bpm.music.services.PerfilService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import java.util.List;
import java.util.Map;

/**
 * Perfiles públicos y amigos.
 *   GET    /api/perfiles?q=…                      buscar personas
 *   GET    /api/perfiles/{username}               perfil (según su privacidad)
 *   POST   /api/perfiles/{username}/seguir        seguir
 *   DELETE /api/perfiles/{username}/seguir        dejar de seguir
 *   GET    /api/perfiles/{username}/seguidores | /siguiendo
 *   GET    /api/amigos/actividad                  lo que escuchan las personas que sigues
 *   PUT    /api/perfiles/yo/privacidad            qué datos se muestran en tu perfil
 *   POST   /api/actividad                         el reproductor avisa qué está sonando
 */
@RestController
@RequestMapping("/api")
public class PerfilController {

    private final PerfilService perfiles;
    private final ImagenService imagenes;

    public PerfilController(PerfilService perfiles, ImagenService imagenes) {
        this.perfiles = perfiles;
        this.imagenes = imagenes;
    }

    // ── Foto de portada del perfil ──

    @PostMapping("/perfiles/yo/banner")
    public ResponseEntity<Map<String, Object>> subirBanner(@RequestParam("file") MultipartFile file) {
        try {
            Long id = CurrentUser.id();
            String nombre = imagenes.guardar(file, "banners", "banner_" + id, 1800);
            String url = "/api/perfiles/banner/" + nombre;
            borrarBanner(perfiles.ponerBanner(id, url));
            return ResponseEntity.ok(Map.of("ok", true, "banner", url));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() == null ? "No se pudo guardar la imagen" : e.getMessage()));
        }
    }

    @PutMapping("/perfiles/yo/banner/posicion")
    public Map<String, Object> posicionBanner(@RequestBody Map<String, Object> b) {
        perfiles.posicionBanner(CurrentUser.id(), ((Number) b.getOrDefault("pos", 50)).intValue());
        return Map.of("ok", true);
    }

    @DeleteMapping("/perfiles/yo/banner")
    public Map<String, Object> quitarBanner() {
        borrarBanner(perfiles.ponerBanner(CurrentUser.id(), null));
        return Map.of("ok", true);
    }

    private void borrarBanner(String url) {
        if (url != null && url.startsWith("/api/perfiles/banner/")) imagenes.borrar("banners", url.substring("/api/perfiles/banner/".length()));
    }

    @GetMapping("/perfiles/banner/{archivo}")
    public ResponseEntity<Resource> banner(@PathVariable String archivo) {
        Path p = imagenes.resolver("banners", archivo);
        if (p == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(ImagenService.tipoContenido(archivo)))
                .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable())
                .body(new FileSystemResource(p));
    }

    @GetMapping("/perfiles")
    public List<Map<String, Object>> buscar(@RequestParam(defaultValue = "") String q) {
        return perfiles.buscar(q, CurrentUser.id());
    }

    @GetMapping("/perfiles/{username}")
    public Map<String, Object> perfil(@PathVariable String username) {
        return perfiles.perfil(username, CurrentUser.id());
    }

    @PostMapping("/perfiles/{username}/seguir")
    public ResponseEntity<Map<String, Object>> seguir(@PathVariable String username) {
        try {
            return ResponseEntity.ok(perfiles.seguir(CurrentUser.id(), username));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/perfiles/{username}/seguir")
    public Map<String, Object> dejar(@PathVariable String username) {
        return perfiles.dejarDeSeguir(CurrentUser.id(), username);
    }

    @GetMapping("/perfiles/{username}/seguidores")
    public List<Map<String, Object>> seguidores(@PathVariable String username) {
        return perfiles.seguidores(username, CurrentUser.id());
    }

    @GetMapping("/perfiles/{username}/siguiendo")
    public List<Map<String, Object>> siguiendo(@PathVariable String username) {
        return perfiles.siguiendo(username, CurrentUser.id());
    }

    @GetMapping("/amigos/actividad")
    public List<Map<String, Object>> actividadAmigos() {
        return perfiles.actividadAmigos(CurrentUser.id());
    }

    @PutMapping("/perfiles/yo/privacidad")
    public Map<String, Object> privacidad(@RequestBody Map<String, Object> b) {
        return perfiles.guardarPrivacidad(CurrentUser.id(), b);
    }

    @PostMapping("/actividad")
    public Map<String, Object> actividad(@RequestBody Map<String, Object> b) {
        Object id = b.get("cancionId");
        perfiles.avisarReproduccion(CurrentUser.id(), id == null ? null : ((Number) id).longValue(), Boolean.TRUE.equals(b.get("sonando")));
        return Map.of("ok", true);
    }
}
