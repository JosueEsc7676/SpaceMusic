package com.bpm.music.controllers;

import com.bpm.music.models.Cancion;
import com.bpm.music.services.CancionService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/portada")
public class PortadaController {

    private final CancionService service;

    public PortadaController(CancionService service) {
        this.service = service;
    }

    // 🔥 PORTADA DESDE DB
    @GetMapping("/db/{id}")
    public ResponseEntity<byte[]> getPortada(@PathVariable Long id) {
        try {
            Cancion c = service.getCancionEntity(id);

            if (c.getPortada() != null && c.getPortada().length > 0) {
                return ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_JPEG)
                        .header("Cache-Control", "max-age=86400")
                        .body(c.getPortada());
            }

            return ResponseEntity.notFound().build();

        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    // fallback
    @GetMapping("/id/{id}")
    public ResponseEntity<byte[]> fallback(@PathVariable Long id) {
        return ResponseEntity.notFound().build();
    }
}