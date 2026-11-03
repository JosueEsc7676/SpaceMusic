package com.bpm.music.controllers;

import com.bpm.music.models.UsuarioGuardado;
import com.bpm.music.repositories.UsuarioGuardadoRepository;
import com.bpm.music.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Álbumes y playlists públicas que el usuario guarda en su biblioteca.
 *   GET    /api/usuario/guardados                       → { albumes: [nombres], playlists: [ids] }
 *   POST   /api/usuario/guardados/{album|playlist}?clave=…
 *   DELETE /api/usuario/guardados/{album|playlist}?clave=…
 */
@RestController
@RequestMapping("/api/usuario/guardados")
public class GuardadosController {

    private static final Map<String, String> TIPOS = Map.of("album", "ALBUM", "playlist", "PLAYLIST");
    private final UsuarioGuardadoRepository repo;

    public GuardadosController(UsuarioGuardadoRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public Map<String, Object> listar() {
        List<String> albumes = new ArrayList<>();
        List<Long> playlists = new ArrayList<>();
        for (UsuarioGuardado g : repo.findByUsuarioIdOrderByFechaDesc(CurrentUser.id())) {
            if ("ALBUM".equals(g.getTipo())) albumes.add(g.getClave());
            else {
                try { playlists.add(Long.parseLong(g.getClave())); } catch (NumberFormatException ignored) { }
            }
        }
        return Map.of("albumes", albumes, "playlists", playlists);
    }

    @PostMapping("/{tipo}")
    public ResponseEntity<Map<String, Object>> guardar(@PathVariable String tipo, @RequestParam String clave) {
        String t = TIPOS.get(tipo);
        if (t == null || clave == null || clave.isBlank() || clave.length() > 255) {
            return ResponseEntity.badRequest().body(Map.of("error", "Datos inválidos"));
        }
        Long uid = CurrentUser.id();
        if (!repo.existsByUsuarioIdAndTipoAndClave(uid, t, clave)) {
            repo.save(UsuarioGuardado.builder().usuarioId(uid).tipo(t).clave(clave).build());
        }
        return ResponseEntity.ok(Map.of("ok", true, "guardado", true));
    }

    @DeleteMapping("/{tipo}")
    public ResponseEntity<Map<String, Object>> quitar(@PathVariable String tipo, @RequestParam String clave) {
        String t = TIPOS.get(tipo);
        if (t == null) return ResponseEntity.badRequest().body(Map.of("error", "Datos inválidos"));
        repo.deleteByUsuarioIdAndTipoAndClave(CurrentUser.id(), t, clave);
        return ResponseEntity.ok(Map.of("ok", true, "guardado", false));
    }
}
