package com.bpm.music.controllers;

import com.bpm.music.dtos.CancionDTO;
import com.bpm.music.services.SmartShuffleService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/shuffle")
@CrossOrigin(origins = "*")
public class SmartShuffleController {

    private final SmartShuffleService service;

    public SmartShuffleController(SmartShuffleService service) {
        this.service = service;
    }

    // Cola inteligente: no repite artista consecutivo,
    // pondera por reproducciones, respeta géneros preferidos
    @GetMapping("/cola")
    public ResponseEntity<List<CancionDTO>> generarCola(
            @RequestParam(defaultValue = "20") int cantidad,
            @RequestParam(required = false) String genero,
            @RequestParam(required = false) String artista
    ) {
        return ResponseEntity.ok(service.generarColaInteligente(cantidad, genero, artista));
    }

    // Mix por género
    @GetMapping("/genero/{genero}")
    public ResponseEntity<List<CancionDTO>> mixPorGenero(
            @PathVariable String genero,
            @RequestParam(defaultValue = "20") int cantidad
    ) {
        return ResponseEntity.ok(service.mixPorGenero(genero, cantidad));
    }

    // Mix por artista: sus canciones + artistas similares (mismo género)
    @GetMapping("/artista/{artista}")
    public ResponseEntity<List<CancionDTO>> mixPorArtista(
            @PathVariable String artista,
            @RequestParam(defaultValue = "20") int cantidad
    ) {
        return ResponseEntity.ok(service.mixPorArtista(artista, cantidad));
    }

    // Mix diario: varía según el día, siempre diferente
    @GetMapping("/diario")
    public ResponseEntity<List<CancionDTO>> mixDiario(
            @RequestParam(defaultValue = "25") int cantidad
    ) {
        return ResponseEntity.ok(service.mixDiario(cantidad));
    }

    // Radio inteligente: canciones similares a una dada (mismo artista + mismo género)
    @GetMapping("/similar/{id}")
    public ResponseEntity<List<CancionDTO>> similar(
            @PathVariable Long id,
            @RequestParam(defaultValue = "15") int cantidad
    ) {
        return ResponseEntity.ok(service.generarRadioSimilar(id, cantidad));
    }
}
