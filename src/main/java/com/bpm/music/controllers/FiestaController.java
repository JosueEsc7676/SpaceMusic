package com.bpm.music.controllers;

import com.bpm.music.dtos.CancionDTO;
import com.bpm.music.security.CurrentUser;
import com.bpm.music.services.FiestaService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Modo fiesta. Anfitrión (con sesión): /api/fiesta. Invitados (sin cuenta, con el enlace del QR): /api/fiesta/publica/{token}.
 */
@RestController
@RequestMapping("/api/fiesta")
public class FiestaController {

    private final FiestaService fiesta;

    public FiestaController(FiestaService fiesta) { this.fiesta = fiesta; }

    @PostMapping
    public Map<String, Object> abrir() { return fiesta.abrir(CurrentUser.id()); }

    @GetMapping
    public Map<String, Object> mia() { return fiesta.mia(CurrentUser.id()); }

    @DeleteMapping
    public ResponseEntity<Void> cerrar() {
        fiesta.cerrar(CurrentUser.id());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/tomar/{propuesta}")
    public Map<String, Object> tomar(@PathVariable long propuesta) { return fiesta.tomar(CurrentUser.id(), propuesta); }

    @PostMapping("/sonando")
    public Map<String, Object> sonando(@RequestBody Map<String, Long> b) {
        fiesta.sonando(CurrentUser.id(), b.get("cancionId"));
        return Map.of("ok", true);
    }

    // ── Invitados ──

    @GetMapping("/publica/{token}")
    public Map<String, Object> ver(@PathVariable String token, @RequestParam(required = false) String invitado) {
        return fiesta.verPublica(token, invitado);
    }

    @GetMapping("/publica/{token}/buscar")
    public List<CancionDTO> buscar(@PathVariable String token, @RequestParam String q, HttpServletRequest req) {
        return fiesta.buscarCanciones(token, q, req.getRemoteAddr());
    }

    @PostMapping("/publica/{token}/proponer")
    public Map<String, Object> proponer(@PathVariable String token, @RequestBody Map<String, Object> b, HttpServletRequest req) {
        long id = b.get("cancionId") instanceof Number n ? n.longValue() : -1;
        return fiesta.proponer(token, id, String.valueOf(b.get("invitado")), b.get("nombre") == null ? null : String.valueOf(b.get("nombre")), req.getRemoteAddr());
    }

    @PostMapping("/publica/{token}/votar/{propuesta}")
    public Map<String, Object> votar(@PathVariable String token, @PathVariable long propuesta, @RequestBody Map<String, String> b, HttpServletRequest req) {
        return fiesta.votar(token, propuesta, b.get("invitado"), req.getRemoteAddr());
    }
}
