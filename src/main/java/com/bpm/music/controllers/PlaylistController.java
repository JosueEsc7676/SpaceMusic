package com.bpm.music.controllers;

import com.bpm.music.dtos.PlaylistDTO;
import com.bpm.music.models.Playlist;
import com.bpm.music.services.PlaylistService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/playlists")
@CrossOrigin(origins = "*")
public class PlaylistController {

    private final PlaylistService service;

    public PlaylistController(PlaylistService service) {
        this.service = service;
    }

    // ✅ Solo devuelve las playlists del usuario autenticado
    @GetMapping
    public ResponseEntity<List<PlaylistDTO>> listar(Authentication auth) {
        Long userId = getUserId(auth);
        return ResponseEntity.ok(service.listarPorUsuario(userId));
    }

    // ✅ Solo devuelve la playlist si pertenece al usuario
    @GetMapping("/{id}")
    public ResponseEntity<PlaylistDTO> getById(@PathVariable Long id, Authentication auth) {
        Long userId = getUserId(auth);
        PlaylistDTO playlist = service.getById(id, userId);
        if (playlist == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(playlist);
    }

    // ✅ Crea playlist asignada al usuario
    @PostMapping
    public ResponseEntity<PlaylistDTO> crear(@RequestBody Playlist playlist, Authentication auth) {
        Long userId = getUserId(auth);
        return ResponseEntity.ok(service.crear(playlist, userId));
    }

    // ✅ Solo puede agregar a sus propias playlists
    @PostMapping("/{playlistId}/canciones/{cancionId}")
    public ResponseEntity<PlaylistDTO> agregarCancion(
            @PathVariable Long playlistId,
            @PathVariable Long cancionId,
            Authentication auth) {
        Long userId = getUserId(auth);
        return ResponseEntity.ok(service.agregarCancion(playlistId, cancionId, userId));
    }

    // ✅ Solo puede remover de sus propias playlists
    @DeleteMapping("/{playlistId}/canciones/{cancionId}")
    public ResponseEntity<PlaylistDTO> removerCancion(
            @PathVariable Long playlistId,
            @PathVariable Long cancionId,
            Authentication auth) {
        Long userId = getUserId(auth);
        return ResponseEntity.ok(service.removerCancion(playlistId, cancionId, userId));
    }

    // ✅ Solo puede borrar sus propias playlists
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> eliminar(@PathVariable Long id, Authentication auth) {
        Long userId = getUserId(auth);
        service.eliminar(id, userId);
        return ResponseEntity.ok().build();
    }

    // ── Helper: extraer userId del token JWT ──
    private Long getUserId(Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) return null;
        return (Long) auth.getPrincipal();
    }
}
