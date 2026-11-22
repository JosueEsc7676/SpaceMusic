package com.bpm.music.controllers;

import com.bpm.music.security.CurrentUser;
import com.bpm.music.soporte.MonitorService;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Solo duraciones, sin títulos, URLs ni contenido. Resultados visibles únicamente en administración. */
@RestController
@RequestMapping("/api/usuario/rendimiento")
public class RendimientoUsuarioController {
    public record Muestra(String metrica, String plataforma, Double ms) {}
    private record Ventana(long minuto, int cantidad) {}
    private final ConcurrentHashMap<Long, Ventana> limites = new ConcurrentHashMap<>();
    private final MonitorService monitor;
    public RendimientoUsuarioController(MonitorService monitor) { this.monitor = monitor; }
    @PostMapping
    public ResponseEntity<Void> registrar(@RequestBody Muestra muestra) {
        if (muestra.ms() == null || !Double.isFinite(muestra.ms()) || muestra.ms() < 0 || muestra.ms() > 120000
                || muestra.metrica() == null || !Set.of("audioInicio", "corte").contains(muestra.metrica())
                || muestra.plataforma() == null || !Set.of("web", "android", "ios").contains(muestra.plataforma()))
            throw new IllegalArgumentException("Muestra no válida");
        long minuto = System.currentTimeMillis() / 60000;
        if (limites.size() >= 1000) limites.entrySet().removeIf(e -> e.getValue().minuto() < minuto);
        Long uid = CurrentUser.id();
        if (limites.size() >= 1000 && !limites.containsKey(uid)) return ResponseEntity.status(429).build();
        Ventana v = limites.compute(uid, (k, anterior) -> anterior == null || anterior.minuto() != minuto
                ? new Ventana(minuto, 1) : new Ventana(minuto, Math.min(21, anterior.cantidad() + 1)));
        if (v.cantidad() > 20) return ResponseEntity.status(429).build();
        monitor.registrarCliente("cliente/" + muestra.plataforma() + "/" + muestra.metrica(), muestra.ms());
        return ResponseEntity.noContent().build();
    }
}
