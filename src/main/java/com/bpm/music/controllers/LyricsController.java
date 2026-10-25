// controllers/LyricsController.java
package com.bpm.music.controllers;

import com.bpm.music.models.Cancion;
import com.bpm.music.repositories.CancionRepository;
import com.bpm.music.services.LyricsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/letras")
@CrossOrigin(origins = "*")
public class LyricsController {

    @Autowired
    private CancionRepository cancionRepository;

    @Autowired
    private LyricsService lyricsService;

    // 🔥 SUBIR ARCHIVO LRC (CORREGIDO)
    @PostMapping("/subir/{cancionId}")
    public ResponseEntity<?> subirLrc(
            @PathVariable Long cancionId,
            @RequestParam("file") MultipartFile file
    ) {
        try {
            // Verificar si la canción existe
            Cancion cancion = cancionRepository.findById(cancionId).orElse(null);
            if (cancion == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Canción no encontrada"));
            }

            // Verificar si ya tiene letra sincronizada
            if (cancion.getLetraSincronizada() != null && !cancion.getLetraSincronizada().isEmpty()) {
                return ResponseEntity.ok(Map.of("message", "⚠️ Ya existe una letra sincronizada para esta canción"));
            }

            // Leer el contenido del archivo
            String contenido = new String(file.getBytes(), StandardCharsets.UTF_8);

            // Validar formato LRC
            if (!lyricsService.esLrcValido(contenido)) {
                return ResponseEntity.badRequest().body(Map.of("error", "Formato LRC inválido. El archivo debe contener timestamps como [00:00.00]"));
            }

            // Guardar la letra sincronizada
            cancion.setLetraSincronizada(contenido);
            cancionRepository.save(cancion);

            return ResponseEntity.ok(Map.of("message", "✅ Letra LRC subida correctamente"));

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(Map.of("error", "Error al subir el archivo: " + e.getMessage()));
        }
    }

    // 🔥 OBTENER LETRA DE UNA CANCIÓN
    @GetMapping("/{cancionId}")
    public ResponseEntity<?> obtenerLetra(@PathVariable Long cancionId) {
        Cancion cancion = cancionRepository.findById(cancionId).orElse(null);
        if (cancion == null) {
            return ResponseEntity.notFound().build();
        }

        Map<String, String> response = new HashMap<>();
        response.put("letra", cancion.getLetra() != null ? cancion.getLetra() : "");
        response.put("letraSincronizada", cancion.getLetraSincronizada() != null ? cancion.getLetraSincronizada() : "");
        return ResponseEntity.ok(response);
    }
}