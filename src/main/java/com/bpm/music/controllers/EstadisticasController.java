package com.bpm.music.controllers;

import com.bpm.music.dtos.CancionDTO;
import com.bpm.music.services.EstadisticasService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Estadísticas globales (solo ADMIN, ver SecurityConfig). */
@RestController
@RequestMapping("/api/estadisticas")
public class EstadisticasController {

    private final EstadisticasService service;

    public EstadisticasController(EstadisticasService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> resumen() {
        return service.resumen();
    }

    @GetMapping("/top-artistas")
    public List<Map<String, Object>> topArtistas(@RequestParam(defaultValue = "10") int limite) {
        return service.topArtistas(Math.min(limite, 100));
    }

    @GetMapping("/top-generos")
    public List<Map<String, Object>> topGeneros() {
        return service.topGeneros();
    }

    @GetMapping("/por-mes")
    public List<Map<String, Object>> porMes() {
        return service.cancionesPorMes();
    }

    @GetMapping("/tendencias")
    public List<CancionDTO> tendencias(@RequestParam(defaultValue = "7") int dias,
                                       @RequestParam(defaultValue = "20") int limite) {
        return service.tendencias(Math.max(1, dias), Math.min(limite, 100));
    }
}
