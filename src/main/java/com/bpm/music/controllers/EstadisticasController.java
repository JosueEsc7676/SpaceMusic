package com.bpm.music.controllers;

import com.bpm.music.services.EstadisticasService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/estadisticas")
@CrossOrigin(origins = "*")
public class EstadisticasController {

    private final EstadisticasService service;

    public EstadisticasController(EstadisticasService service) {
        this.service = service;
    }

    // Resumen general: totales, horas, géneros, etc.
    @GetMapping
    public ResponseEntity<Map<String, Object>> getResumen() {
        return ResponseEntity.ok(service.getResumenGeneral());
    }

    // Top artistas por reproducciones
    @GetMapping("/top-artistas")
    public ResponseEntity<Object> getTopArtistas(
            @RequestParam(defaultValue = "10") int limite) {
        return ResponseEntity.ok(service.getTopArtistas(limite));
    }

    // Top géneros
    @GetMapping("/top-generos")
    public ResponseEntity<Object> getTopGeneros() {
        return ResponseEntity.ok(service.getTopGeneros());
    }

    // Canciones agregadas por mes (últimos 12 meses)
    @GetMapping("/por-mes")
    public ResponseEntity<Object> getPorMes() {
        return ResponseEntity.ok(service.getCancionesPorMes());
    }

    // Distribución de duración de canciones
    @GetMapping("/duracion")
    public ResponseEntity<Object> getDistribucionDuracion() {
        return ResponseEntity.ok(service.getDistribucionDuracion());
    }
}
