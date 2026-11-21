package com.bpm.music.controllers;

import com.bpm.music.security.CurrentUser;
import com.bpm.music.services.TvVinculoService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Iniciar sesión en la TV con el teléfono (QR, código o «misma red wifi»). Ver {@link TvVinculoService}.
 * Crear y consultar el estado no piden sesión (la TV aún no la tiene); ver, aprobar y buscar cercanas sí.
 */
@RestController
@RequestMapping("/api/auth/tv")
public class TvVinculoController {

    private final TvVinculoService vinculos;

    public TvVinculoController(TvVinculoService vinculos) { this.vinculos = vinculos; }

    @PostMapping("/vinculo")
    public Map<String, Object> crear(@RequestBody(required = false) Map<String, String> b, HttpServletRequest req) {
        return vinculos.crear(b == null ? null : b.get("nombre"), req.getRemoteAddr());
    }

    @GetMapping("/vinculo/estado")
    public Map<String, Object> estado(@RequestParam String secreto) { return vinculos.estado(secreto); }

    @GetMapping("/vinculo/{codigo}")
    public Map<String, Object> ver(@PathVariable String codigo) {
        CurrentUser.id();
        return vinculos.ver(codigo);
    }

    @PostMapping("/vinculo/{codigo}/aprobar")
    public Map<String, Object> aprobar(@PathVariable String codigo) {
        return vinculos.aprobar(CurrentUser.id(), codigo);
    }

    @GetMapping("/cercanas")
    public List<Map<String, Object>> cercanas(HttpServletRequest req) {
        CurrentUser.id();
        return vinculos.cercanas(req.getRemoteAddr());
    }
}
