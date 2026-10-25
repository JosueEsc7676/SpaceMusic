// controllers/PlaybackController.java
package com.bpm.music.controllers;

import com.bpm.music.dtos.CancionDTO;
import com.bpm.music.services.CancionService;
import com.bpm.music.services.SmartRecommendationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Endpoints de reproducción inteligente:
 *
 *   GET  /api/play/{id}         → reproduce una canción (alias de /api/canciones/{id})
 *   GET  /api/next/{currentId}  → siguiente canción recomendada
 *   POST /api/history           → registrar reproducción en historial
 */
@RestController
@CrossOrigin(origins = "*")
public class PlaybackController {

    private final CancionService cancionService;
    private final SmartRecommendationService recommendationService;

    public PlaybackController(CancionService cancionService,
                               SmartRecommendationService recommendationService) {
        this.cancionService = cancionService;
        this.recommendationService = recommendationService;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // GET /api/play/{id}
    // Devuelve la canción a reproducir (igual que /api/canciones/{id})
    // Aquí se puede extender para logging, permisos, etc.
    // ═══════════════════════════════════════════════════════════════════════
    @GetMapping("/api/play/{id}")
    public ResponseEntity<CancionDTO> playCancion(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(cancionService.getById(id));
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // GET /api/next/{currentId}?sesion=abc123
    //
    // Devuelve la siguiente canción recomendada con scoring:
    //   +10 mismo artista, +5 mismo género, +3 mismo álbum
    //   Excluye las últimas 20 canciones del historial de sesión
    // ═══════════════════════════════════════════════════════════════════════
    @GetMapping("/api/next/{currentId}")
    public ResponseEntity<CancionDTO> getSiguiente(
            @PathVariable Long currentId,
            @RequestParam(required = false, defaultValue = "anonymous") String sesion) {

        try {
            CancionDTO siguiente = recommendationService.getSiguienteRecomendada(currentId, sesion);
            return ResponseEntity.ok(siguiente);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // POST /api/history
    //
    // Body JSON:
    // {
    //   "cancionId": 42,
    //   "sesionId": "abc123",
    //   "segundosEscuchados": 180,   // 0 = escuchó completa
    //   "fuente": "RADIO"            // MANUAL | RADIO | QUEUE | PLAYLIST | SHUFFLE
    // }
    // ═══════════════════════════════════════════════════════════════════════
    @PostMapping("/api/history")
    public ResponseEntity<Void> registrarHistorial(@RequestBody Map<String, Object> body) {
        try {
            Long cancionId = Long.valueOf(body.get("cancionId").toString());
            String sesionId = body.getOrDefault("sesionId", "anonymous").toString();
            Integer segundos = body.containsKey("segundosEscuchados")
                    ? Integer.valueOf(body.get("segundosEscuchados").toString())
                    : 0;
            String fuente = body.getOrDefault("fuente", "MANUAL").toString();

            recommendationService.registrarReproduccion(cancionId, sesionId, segundos, fuente);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }
}
