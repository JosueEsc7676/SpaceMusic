package com.bpm.music.descargas;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Administración → Descargar música (solo administradores; los cambios quedan en la auditoría). */
@RestController
@RequestMapping("/api/admin/descargas")
@PreAuthorize("hasRole('ADMIN')")
public class DescargasController {

    private final DescargasService service;

    public DescargasController(DescargasService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> estado(@RequestParam(defaultValue = "120") int lineas) {
        return service.estado(lineas);
    }

    @PostMapping
    public Map<String, Object> encolar(@RequestBody Map<String, String> b) {
        return service.encolar(b.get("texto"));
    }

    @PostMapping("/reintentar")
    public Map<String, Object> reintentar() {
        return service.reintentar();
    }

    @PostMapping("/detener")
    public Map<String, Object> detener() {
        return service.detener();
    }

    @PostMapping("/reanudar")
    public Map<String, Object> reanudar() {
        return service.reanudar();
    }

    @DeleteMapping("/cola/{nombre}")
    public ResponseEntity<Void> quitar(@PathVariable String nombre) {
        service.quitarDeCola(nombre);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/fallidas")
    public ResponseEntity<Void> limpiarFallidas() {
        service.limpiarFallidas();
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/spotify")
    public ResponseEntity<Void> spotify(@RequestBody Map<String, String> b) {
        service.guardarSpotify(b.get("clientId"), b.get("clientSecret"));
        return ResponseEntity.noContent().build();
    }
}
