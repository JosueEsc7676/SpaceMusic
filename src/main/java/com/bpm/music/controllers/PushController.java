package com.bpm.music.controllers;

import com.bpm.music.security.CurrentUser;
import com.bpm.music.services.PushService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Avisos al instante en Android con UnifiedPush (ntfy u otra app distribuidora). */
@RestController
@RequestMapping("/api/push")
public class PushController {

    private final PushService push;

    public PushController(PushService push) { this.push = push; }

    @PostMapping("/destino")
    public Map<String, Object> registrar(@RequestBody Map<String, String> b) {
        return push.registrar(CurrentUser.id(), b.get("endpoint"), b.get("dispositivo"));
    }

    @DeleteMapping("/destino")
    public ResponseEntity<Void> quitar(@RequestParam String endpoint) {
        push.quitar(CurrentUser.id(), endpoint);
        return ResponseEntity.noContent().build();
    }
}
