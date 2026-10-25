package com.bpm.music.controllers;

import com.bpm.music.dtos.CancionDTO;
import com.bpm.music.services.UsuarioService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.nio.file.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/usuario")
@CrossOrigin(origins = "*")
public class UsuarioController {

    @Autowired private UsuarioService service;

    @Value("${music.playlist-images-path:./playlist-images}")
    private String uploadsPath;

    // ── Favoritos del usuario ──
    @GetMapping("/favoritos")
    public ResponseEntity<List<CancionDTO>> getFavoritos(Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        return ResponseEntity.ok(service.getFavoritos(userId));
    }

    @PutMapping("/favoritos/{cancionId}")
    public ResponseEntity<Map<String, Object>> toggleFavorito(
            @PathVariable Long cancionId, Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        return ResponseEntity.ok(service.toggleFavorito(userId, cancionId));
    }

    @GetMapping("/favoritos/{cancionId}/check")
    public ResponseEntity<Map<String, Object>> checkFavorito(
            @PathVariable Long cancionId, Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        boolean fav = service.isFavorito(userId, cancionId);
        return ResponseEntity.ok(Map.of("favorito", fav));
    }

    // ── Registrar reproducción del usuario ──
    @PostMapping("/reproduccion/{cancionId}")
    public ResponseEntity<Void> registrarReproduccion(
            @PathVariable Long cancionId,
            @RequestParam(defaultValue = "unknown") String contexto,
            Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        service.registrarReproduccion(userId, cancionId, contexto);
        return ResponseEntity.ok().build();
    }

    // ── Estadísticas propias ──
    @GetMapping("/estadisticas")
    public ResponseEntity<Map<String, Object>> getEstadisticas(Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        return ResponseEntity.ok(service.getEstadisticas(userId));
    }

    // ── Servir avatar ──
    @GetMapping("/avatar/{filename}")
    public ResponseEntity<byte[]> getAvatar(@PathVariable String filename) {
        try {
            Path ruta = Paths.get(uploadsPath + "/avatars", filename);
            if (!Files.exists(ruta)) return ResponseEntity.notFound().build();
            byte[] data = Files.readAllBytes(ruta);
            String type = Files.probeContentType(ruta);
            if (type == null) type = "image/jpeg";
            return ResponseEntity.ok()
                    .header("Content-Type", type)
                    .header("Cache-Control", "max-age=86400")
                    .body(data);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }
}
