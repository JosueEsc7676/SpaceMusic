package com.bpm.music.controllers;

import com.bpm.music.security.CurrentUser;
import com.bpm.music.services.ControlRemotoService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Controlar la música de otro dispositivo con la misma cuenta (web, Android e iPhone). */
@RestController
@RequestMapping("/api/control")
public class ControlRemotoController {

    private final ControlRemotoService control;

    public ControlRemotoController(ControlRemotoService control) { this.control = control; }

    /** Estado de este dispositivo: {dispositivo, nombre, plataforma, cancionId, segundo, duracion, sonando, volumen}. */
    @PostMapping("/presencia")
    public ResponseEntity<Void> presencia(@RequestBody Map<String, Object> b) {
        control.presencia(CurrentUser.id(), b);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/presencia/{dispositivo}")
    public ResponseEntity<Void> salir(@PathVariable String dispositivo) {
        control.salir(CurrentUser.id(), dispositivo);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/dispositivos")
    public List<Map<String, Object>> dispositivos(@RequestParam(required = false) String yo) {
        return control.lista(CurrentUser.id(), yo);
    }

    /** Orden para otro dispositivo: {accion, de, segundo | volumen | cancionIds, indice}. */
    @PostMapping("/{dispositivo}/orden")
    public ResponseEntity<Void> orden(@PathVariable String dispositivo, @RequestBody Map<String, Object> b) {
        control.orden(CurrentUser.id(), dispositivo, b.get("de") instanceof String s ? s : null,
                Objects.toString(b.get("accion"), ""), b);
        return ResponseEntity.noContent().build();
    }
}
