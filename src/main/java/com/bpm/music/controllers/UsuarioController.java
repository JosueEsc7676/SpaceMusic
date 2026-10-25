package com.bpm.music.controllers;

import com.bpm.music.dtos.CancionDTO;
import com.bpm.music.security.CurrentUser;
import com.bpm.music.services.AuthService;
import com.bpm.music.services.ImagenService;
import com.bpm.music.services.UsuarioService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/usuario")
public class UsuarioController {

    private final UsuarioService usuarios;
    private final AuthService auth;
    private final ImagenService imagenes;

    public UsuarioController(UsuarioService usuarios, AuthService auth, ImagenService imagenes) {
        this.usuarios = usuarios;
        this.auth = auth;
        this.imagenes = imagenes;
    }

    // ── Favoritos ──

    @GetMapping("/favoritos")
    public List<CancionDTO> favoritos() {
        return usuarios.favoritos(CurrentUser.id());
    }

    @GetMapping("/favoritos/ids")
    public List<Long> favoritosIds() {
        return usuarios.favoritosIds(CurrentUser.id());
    }

    /** Alterna el favorito (compatibilidad). */
    @PutMapping("/favoritos/{cancionId}")
    public Map<String, Object> toggle(@PathVariable Long cancionId) {
        return usuarios.toggleFavorito(CurrentUser.id(), cancionId);
    }

    @PostMapping("/favoritos/{cancionId}")
    public Map<String, Object> agregar(@PathVariable Long cancionId) {
        return usuarios.agregarFavorito(CurrentUser.id(), cancionId);
    }

    @DeleteMapping("/favoritos/{cancionId}")
    public Map<String, Object> quitar(@PathVariable Long cancionId) {
        return usuarios.quitarFavorito(CurrentUser.id(), cancionId);
    }

    @GetMapping("/favoritos/{cancionId}/check")
    public Map<String, Object> check(@PathVariable Long cancionId) {
        return Map.of("favorito", usuarios.favoritosIds(CurrentUser.id()).contains(cancionId));
    }

    // ── Historial y estadísticas ──

    @PostMapping("/reproduccion/{cancionId}")
    public ResponseEntity<Void> reproduccion(@PathVariable Long cancionId,
                                             @RequestParam(defaultValue = "otro") String contexto,
                                             @RequestParam(required = false) Integer segundos) {
        usuarios.registrarReproduccion(CurrentUser.id(), cancionId, contexto, segundos);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/recientes")
    public List<CancionDTO> recientes(@RequestParam(defaultValue = "30") int limite) {
        return usuarios.recientes(CurrentUser.id(), Math.min(Math.max(limite, 1), 100));
    }

    @GetMapping("/estadisticas")
    public Map<String, Object> estadisticas(@RequestParam(defaultValue = "0") int dias) {
        return usuarios.estadisticas(CurrentUser.id(), Math.max(0, dias));
    }

    // ── Perfil ──

    @PutMapping("/perfil")
    public Map<String, Object> perfil(@RequestBody Map<String, String> b) {
        return auth.actualizarPerfil(CurrentUser.id(), b.get("nombreDisplay"));
    }

    @PostMapping("/cambiar-password")
    public ResponseEntity<Map<String, Object>> cambiarPassword(@RequestBody Map<String, String> b) {
        return respuesta(auth.cambiarPassword(CurrentUser.id(), b.get("actual"), b.get("nueva")));
    }

    @PostMapping("/cambiar-email")
    public ResponseEntity<Map<String, Object>> cambiarEmail(@RequestBody Map<String, String> b) {
        return respuesta(auth.cambiarEmail(CurrentUser.id(), b.get("nuevoEmail"), b.get("password")));
    }

    @PostMapping("/solicitar-colaborador")
    public ResponseEntity<Map<String, Object>> solicitar(@RequestBody Map<String, String> b) {
        return respuesta(auth.solicitarColaborador(CurrentUser.id(), b.get("motivo")));
    }

    @GetMapping("/avatar/{archivo}")
    public ResponseEntity<Resource> avatar(@PathVariable String archivo) {
        Path p = imagenes.resolver("avatars", archivo);
        if (p == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(ImagenService.tipoContenido(archivo)))
                .cacheControl(CacheControl.maxAge(30, TimeUnit.DAYS).cachePublic())
                .body(new FileSystemResource(p));
    }

    private static ResponseEntity<Map<String, Object>> respuesta(Map<String, Object> r) {
        return r.containsKey("error") ? ResponseEntity.badRequest().body(r) : ResponseEntity.ok(r);
    }
}
