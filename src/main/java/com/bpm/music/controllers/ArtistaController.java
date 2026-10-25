package com.bpm.music.controllers;

import com.bpm.music.dtos.ArtistaDTO;
import com.bpm.music.services.ArtistaService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/artistas")
@CrossOrigin(origins = "*")
public class ArtistaController {

    private final ArtistaService service;

    public ArtistaController(ArtistaService service) {
        this.service = service;
    }

    // Lista todos los artistas con conteo de canciones y álbumes
    @GetMapping
    public ResponseEntity<List<ArtistaDTO>> listarArtistas() {
        return ResponseEntity.ok(service.listarArtistas());
    }

    // Detalle de un artista: sus álbumes y canciones agrupadas
    @GetMapping("/{nombre}")
    public ResponseEntity<Map<String, Object>> getArtista(@PathVariable String nombre) {
        Map<String, Object> detalle = service.getDetalleArtista(nombre);
        if (detalle == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(detalle);
    }

    // Solo las canciones de un artista ordenadas por reproducciones
    @GetMapping("/{nombre}/top")
    public ResponseEntity<Map<String, Object>> getTopArtista(@PathVariable String nombre) {
        return ResponseEntity.ok(service.getTopCancionesArtista(nombre));
    }
}
