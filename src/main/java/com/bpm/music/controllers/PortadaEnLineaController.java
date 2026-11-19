package com.bpm.music.controllers;

import com.bpm.music.dtos.CancionDTO;
import com.bpm.music.services.PortadaEnLineaService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Portada original en HD desde internet (iTunes y Deezer) para una canción. */
@RestController
@RequestMapping("/api/canciones/{id}/portada")
@PreAuthorize("hasAnyRole('ADMIN','COLABORADOR')")
public class PortadaEnLineaController {

    private final PortadaEnLineaService portadas;

    public PortadaEnLineaController(PortadaEnLineaService portadas) {
        this.portadas = portadas;
    }

    /** Opciones de portada; `q` busca con otro texto. */
    @GetMapping("/buscar")
    public List<Map<String, Object>> buscar(@PathVariable Long id, @RequestParam(required = false) String q) {
        return portadas.buscar(id, q);
    }

    /** Pone la portada elegida (dirección de una de las opciones). */
    @PostMapping("/en-linea")
    public CancionDTO aplicar(@PathVariable Long id, @RequestBody Map<String, String> b) throws Exception {
        return portadas.aplicar(id, b.get("url"));
    }
}
