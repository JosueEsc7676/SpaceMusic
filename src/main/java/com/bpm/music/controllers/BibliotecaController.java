package com.bpm.music.controllers;

import com.bpm.music.dtos.AlbumDTO;
import com.bpm.music.dtos.ArtistaDTO;
import com.bpm.music.dtos.CancionDTO;
import com.bpm.music.security.CurrentUser;
import com.bpm.music.services.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Navegación de la biblioteca: inicio, búsqueda, álbumes, artistas,
 * géneros, radio y mezclas.
 */
@RestController
@RequestMapping("/api")
public class BibliotecaController {

    private final BibliotecaService biblioteca;
    private final UsuarioService usuarios;
    private final RecomendacionService recomendaciones;
    private final PlaylistService playlists;
    private final EstadisticasService estadisticas;

    public BibliotecaController(BibliotecaService biblioteca, UsuarioService usuarios,
                                RecomendacionService recomendaciones, PlaylistService playlists,
                                EstadisticasService estadisticas) {
        this.biblioteca = biblioteca;
        this.usuarios = usuarios;
        this.recomendaciones = recomendaciones;
        this.playlists = playlists;
        this.estadisticas = estadisticas;
    }

    /** Todo lo que necesita la pantalla de inicio en una sola petición. */
    @GetMapping("/inicio")
    public Map<String, Object> inicio() {
        Long uid = CurrentUser.id();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("totalCanciones", biblioteca.total());
        r.put("recientes", usuarios.recientes(uid, 12));
        r.put("mixes", recomendaciones.mixesPara(uid));
        r.put("tusFavoritas", usuarios.masEscuchadas(uid, 30, 12));
        r.put("tendencias", estadisticas.tendencias(7, 12));
        r.put("top", biblioteca.top(12));
        r.put("recienAgregadas", biblioteca.recienAgregadas(12));
        r.put("albumesRecientes", biblioteca.albumesRecientes(12));
        r.put("artistas", biblioteca.artistas().stream().limit(12).toList());
        r.put("generos", biblioteca.generos().stream().limit(16).toList());
        r.put("playlistsPublicas", playlists.publicas(uid).stream().limit(12).toList());
        return r;
    }

    @GetMapping("/buscar")
    public Map<String, Object> buscar(@RequestParam(defaultValue = "") String q) {
        Map<String, Object> r = new LinkedHashMap<>(biblioteca.buscarTodo(q));
        String nq = BibliotecaService.normalizar(q);
        Long uid = CurrentUser.id();
        if (!nq.isEmpty()) {
            List<Object> pls = new ArrayList<>();
            playlists.mias(uid).stream().filter(p -> BibliotecaService.normalizar(p.getNombre()).contains(nq)).forEach(pls::add);
            playlists.publicas(uid).stream().filter(p -> BibliotecaService.normalizar(p.getNombre()).contains(nq)).forEach(pls::add);
            r.put("playlists", pls);
            r.put("generos", biblioteca.generos().stream()
                    .filter(g -> BibliotecaService.normalizar((String) g.get("nombre")).contains(nq)).limit(6).toList());
        } else {
            r.put("playlists", List.of());
            r.put("generos", List.of());
        }
        return r;
    }

    // ── Álbumes ──

    @GetMapping("/albumes")
    public List<AlbumDTO> albumes() {
        return biblioteca.albumes();
    }

    @GetMapping("/albumes/detalle")
    public ResponseEntity<AlbumDTO> album(@RequestParam String nombre) {
        return biblioteca.album(nombre).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    // ── Artistas ──

    @GetMapping("/artistas")
    public List<ArtistaDTO> artistas() {
        return biblioteca.artistas();
    }

    @GetMapping("/artistas/{nombre}")
    public ResponseEntity<Map<String, Object>> artista(@PathVariable String nombre) {
        return biblioteca.artista(nombre).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    // ── Géneros ──

    @GetMapping("/generos")
    public List<Map<String, Object>> generos() {
        return biblioteca.generos();
    }

    @GetMapping("/generos/{nombre}")
    public Map<String, Object> genero(@PathVariable String nombre) {
        List<CancionDTO> cs = biblioteca.cancionesDeGenero(nombre).stream()
                .sorted(Comparator.comparingInt(CancionDTO::getReproducciones).reversed()).toList();
        if (cs.isEmpty()) throw new NoSuchElementException("Género no encontrado");
        return Map.of("nombre", cs.get(0).getGenero(), "canciones", cs);
    }

    // ── Reproducción, radio y mezclas ──

    /** Registra una escucha: {"cancionId": 1, "contexto": "album", "segundos": 95} */
    @PostMapping("/reproducciones")
    public ResponseEntity<Void> reproduccion(@RequestBody Map<String, Object> b) {
        Long id = Long.valueOf(String.valueOf(b.get("cancionId")));
        Integer seg = b.get("segundos") == null ? null : (int) Double.parseDouble(String.valueOf(b.get("segundos")));
        usuarios.registrarReproduccion(CurrentUser.id(), id, Objects.toString(b.get("contexto"), "otro"), seg);
        return ResponseEntity.noContent().build();
    }

    /** Compatibilidad con la versión anterior. */
    @PostMapping("/history")
    public ResponseEntity<Void> historial(@RequestBody Map<String, Object> b) {
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/next/{actualId}")
    public CancionDTO siguiente(@PathVariable Long actualId) {
        return recomendaciones.siguiente(actualId, CurrentUser.id());
    }

    @GetMapping("/radio/{id}")
    public List<CancionDTO> radio(@PathVariable Long id,
                                  @RequestParam(defaultValue = "25") int cantidad,
                                  @RequestParam(required = false) List<Long> excluir) {
        return recomendaciones.radio(id, Math.min(Math.max(cantidad, 1), 100), CurrentUser.id(), excluir);
    }

    @GetMapping("/mixes")
    public List<Map<String, Object>> mixes() {
        return recomendaciones.mixesPara(CurrentUser.id());
    }

    @GetMapping("/mixes/{id}")
    public List<CancionDTO> mix(@PathVariable String id, @RequestParam(defaultValue = "30") int cantidad) {
        return recomendaciones.obtenerMix(id, CurrentUser.id(), Math.min(Math.max(cantidad, 1), 100));
    }

    // ── Rutas de la versión anterior (/api/shuffle/...) ──

    @GetMapping("/shuffle/cola")
    public List<CancionDTO> cola(@RequestParam(defaultValue = "20") int cantidad,
                                 @RequestParam(required = false) String genero,
                                 @RequestParam(required = false) String artista) {
        return recomendaciones.mixAleatorio(Math.min(cantidad, 100), genero, artista);
    }

    @GetMapping("/shuffle/genero/{genero}")
    public List<CancionDTO> shuffleGenero(@PathVariable String genero, @RequestParam(defaultValue = "20") int cantidad) {
        return recomendaciones.mixAleatorio(Math.min(cantidad, 100), genero, null);
    }

    @GetMapping("/shuffle/artista/{artista}")
    public List<CancionDTO> shuffleArtista(@PathVariable String artista, @RequestParam(defaultValue = "20") int cantidad) {
        return recomendaciones.mixArtista(artista, Math.min(cantidad, 100));
    }

    @GetMapping("/shuffle/diario")
    public List<CancionDTO> shuffleDiario(@RequestParam(defaultValue = "25") int cantidad) {
        return recomendaciones.mixDiario(CurrentUser.id(), Math.min(cantidad, 100));
    }

    @GetMapping("/shuffle/similar/{id}")
    public List<CancionDTO> similar(@PathVariable Long id, @RequestParam(defaultValue = "15") int cantidad) {
        return recomendaciones.radio(id, Math.min(cantidad, 100), CurrentUser.id(), null);
    }
}
