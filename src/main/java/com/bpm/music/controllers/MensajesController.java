package com.bpm.music.controllers;

import com.bpm.music.security.CurrentUser;
import com.bpm.music.services.MensajesService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Mensajes entre amigos y notificaciones.
 *   GET  /api/mensajes                       conversaciones
 *   GET  /api/mensajes/amigos                amigos para empezar una conversación
 *   GET  /api/mensajes/{username}?antes=id   mensajes con esa persona (marca como leídos)
 *   POST /api/mensajes/{username}            enviar { texto, cancionId? }
 *   GET  /api/notificaciones                 avisos
 *   POST /api/notificaciones/leidas          marcar todos como leídos
 *   GET  /api/notificaciones/resumen         contadores (las apps lo consultan cada pocos segundos)
 */
@RestController
@RequestMapping("/api")
public class MensajesController {

    private final MensajesService servicio;

    public MensajesController(MensajesService servicio) {
        this.servicio = servicio;
    }

    @GetMapping("/mensajes")
    public List<Map<String, Object>> conversaciones() {
        return servicio.conversaciones(CurrentUser.id());
    }

    @GetMapping("/mensajes/amigos")
    public List<Map<String, Object>> amigos() {
        return servicio.amigos(CurrentUser.id());
    }

    @GetMapping("/mensajes/{username}")
    public Map<String, Object> conversacion(@PathVariable String username, @RequestParam(required = false) Long antes) {
        return servicio.conversacion(CurrentUser.id(), username, antes);
    }

    @PostMapping("/mensajes/{username}")
    public ResponseEntity<Map<String, Object>> enviar(@PathVariable String username, @RequestBody Map<String, Object> b) {
        try {
            Object c = b.get("cancionId");
            return ResponseEntity.ok(servicio.enviar(CurrentUser.id(), username, (String) b.get("texto"),
                    c == null ? null : ((Number) c).longValue()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/notificaciones")
    public List<Map<String, Object>> notificaciones() {
        return servicio.notificaciones(CurrentUser.id());
    }

    @PostMapping("/notificaciones/leidas")
    public Map<String, Object> leidas() {
        servicio.marcarLeidas(CurrentUser.id());
        return Map.of("ok", true);
    }

    @GetMapping("/notificaciones/resumen")
    public Map<String, Object> resumen() {
        return servicio.resumen(CurrentUser.id());
    }
}
