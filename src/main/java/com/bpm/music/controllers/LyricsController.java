package com.bpm.music.controllers;

import com.bpm.music.services.LetrasService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/letras")
public class LyricsController {

    private final LetrasService letras;
    private com.bpm.music.services.TraduccionService traduccion;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setTraduccion(com.bpm.music.services.TraduccionService t) { this.traduccion = t; }

    /** La letra traducida línea por línea (es, en, pt, fr, it, de). */
    @GetMapping("/{id}/traduccion")
    public Map<String, Object> traduccion(@PathVariable Long id, @RequestParam(defaultValue = "es") String a) throws Exception {
        if (traduccion == null) throw new IllegalStateException("Traducción no disponible");
        return traduccion.traducir(id, a);
    }

    public LyricsController(LetrasService letras) {
        this.letras = letras;
    }

    @GetMapping("/{id}")
    public Map<String, Object> obtener(@PathVariable Long id) {
        return letras.obtener(id);
    }

    public record AjusteSincronia(Double segundos, String revision) {}

    @PutMapping("/{id}/sincronia")
    @PreAuthorize("hasAnyRole('ADMIN','COLABORADOR')")
    public Map<String, Object> sincronia(@PathVariable Long id, @RequestBody AjusteSincronia ajuste) {
        return letras.guardarSincronia(id, ajuste.segundos(), ajuste.revision());
    }

    /** Busca la letra en internet (LRCLIB) y la guarda si la encuentra. */
    @PostMapping("/{id}/buscar")
    public Map<String, Object> buscar(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean forzar) {
        return letras.buscarEnInternet(id, forzar);
    }

    /** Guarda letra pegada como texto (plano o LRC): {"texto": "..."} */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','COLABORADOR')")
    public Map<String, Object> guardar(@PathVariable Long id, @RequestBody Map<String, String> b) {
        return letras.guardar(id, b.get("texto"));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','COLABORADOR')")
    public ResponseEntity<Void> borrar(@PathVariable Long id) {
        letras.borrar(id);
        return ResponseEntity.noContent().build();
    }

    /** Sube un archivo .lrc (reemplaza la letra que hubiera). */
    @PostMapping("/subir/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','COLABORADOR')")
    public Map<String, Object> subir(@PathVariable Long id, @RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) throw new IllegalArgumentException("El archivo está vacío");
        if (file.getSize() > 512 * 1024) throw new IllegalArgumentException("El archivo es demasiado grande");
        byte[] bytes = file.getBytes();
        String texto = new String(bytes, StandardCharsets.UTF_8);
        if (texto.contains("�")) texto = new String(bytes, StandardCharsets.ISO_8859_1);
        Map<String, Object> r = new HashMap<>(letras.guardar(id, texto));
        r.put("message", ((String) r.get("letraSincronizada")).isEmpty()
                ? "Letra guardada (sin tiempos de sincronización)" : "Letra sincronizada guardada");
        return r;
    }
}
