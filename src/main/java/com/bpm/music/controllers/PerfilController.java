package com.bpm.music.controllers;

import com.bpm.music.security.CurrentUser;
import com.bpm.music.services.PerfilService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Perfiles públicos y amigos.
 *   GET    /api/perfiles?q=…                      buscar personas
 *   GET    /api/perfiles/{username}               perfil (según su privacidad)
 *   POST   /api/perfiles/{username}/seguir        seguir
 *   DELETE /api/perfiles/{username}/seguir        dejar de seguir
 *   GET    /api/perfiles/{username}/seguidores | /siguiendo
 *   GET    /api/amigos/actividad                  lo que escuchan las personas que sigues
 *   PUT    /api/perfiles/yo/privacidad            qué datos se muestran en tu perfil
 *   POST   /api/actividad                         el reproductor avisa qué está sonando
 */
@RestController
@RequestMapping("/api")
public class PerfilController {

    private final PerfilService perfiles;

    public PerfilController(PerfilService perfiles) {
        this.perfiles = perfiles;
    }

    @GetMapping("/perfiles")
    public List<Map<String, Object>> buscar(@RequestParam(defaultValue = "") String q) {
        return perfiles.buscar(q, CurrentUser.id());
    }

    @GetMapping("/perfiles/{username}")
    public Map<String, Object> perfil(@PathVariable String username) {
        return perfiles.perfil(username, CurrentUser.id());
    }

    @PostMapping("/perfiles/{username}/seguir")
    public ResponseEntity<Map<String, Object>> seguir(@PathVariable String username) {
        try {
            return ResponseEntity.ok(perfiles.seguir(CurrentUser.id(), username));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/perfiles/{username}/seguir")
    public Map<String, Object> dejar(@PathVariable String username) {
        return perfiles.dejarDeSeguir(CurrentUser.id(), username);
    }

    @GetMapping("/perfiles/{username}/seguidores")
    public List<Map<String, Object>> seguidores(@PathVariable String username) {
        return perfiles.seguidores(username, CurrentUser.id());
    }

    @GetMapping("/perfiles/{username}/siguiendo")
    public List<Map<String, Object>> siguiendo(@PathVariable String username) {
        return perfiles.siguiendo(username, CurrentUser.id());
    }

    @GetMapping("/amigos/actividad")
    public List<Map<String, Object>> actividadAmigos() {
        return perfiles.actividadAmigos(CurrentUser.id());
    }

    @PutMapping("/perfiles/yo/privacidad")
    public Map<String, Object> privacidad(@RequestBody Map<String, Object> b) {
        return perfiles.guardarPrivacidad(CurrentUser.id(), b);
    }

    @PostMapping("/actividad")
    public Map<String, Object> actividad(@RequestBody Map<String, Object> b) {
        Object id = b.get("cancionId");
        perfiles.avisarReproduccion(CurrentUser.id(), id == null ? null : ((Number) id).longValue(), Boolean.TRUE.equals(b.get("sonando")));
        return Map.of("ok", true);
    }
}
