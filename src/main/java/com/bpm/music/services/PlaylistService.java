package com.bpm.music.services;

import com.bpm.music.dtos.CancionDTO;
import com.bpm.music.dtos.PlaylistDTO;
import com.bpm.music.models.Playlist;
import com.bpm.music.models.Usuario;
import com.bpm.music.repositories.PlaylistRepository;
import com.bpm.music.repositories.UsuarioRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class PlaylistService {

    private static final String SUBCARPETA = null; // las portadas van en la raíz de uploads (compatibilidad)

    private final PlaylistRepository repo;
    private final UsuarioRepository usuarios;
    private final BibliotecaService biblioteca;
    private final ImagenService imagenes;

    public PlaylistService(PlaylistRepository repo, UsuarioRepository usuarios,
                           BibliotecaService biblioteca, ImagenService imagenes) {
        this.repo = repo;
        this.usuarios = usuarios;
        this.biblioteca = biblioteca;
        this.imagenes = imagenes;
    }

    // ═══════════════════════ Consultas ═══════════════════════

    public List<PlaylistDTO> mias(Long userId) {
        return repo.findByUsuarioIdOrderByFechaCreacionDesc(userId).stream()
                .map(p -> dto(p, userId, false)).toList();
    }

    public List<PlaylistDTO> publicas(Long userId) {
        return repo.findPublicasDeOtros(userId).stream()
                .map(p -> dto(p, userId, false))
                .filter(p -> p.getCancionesCount() > 0)
                .toList();
    }

    public PlaylistDTO detalle(Long id, Long userId) {
        Playlist p = repo.findById(id).orElseThrow(() -> new NoSuchElementException("Playlist no encontrada"));
        if (!Objects.equals(p.getUsuarioId(), userId) && !p.isPublica()) {
            throw new NoSuchElementException("Playlist no encontrada");
        }
        return dto(p, userId, true);
    }

    // ═══════════════════════ Crear / editar ═══════════════════════

    @Transactional
    public PlaylistDTO crear(Long userId, String nombre, String descripcion, Boolean publica, MultipartFile imagen) throws IOException {
        Playlist p = Playlist.builder()
                .nombre(validarNombre(nombre))
                .descripcion(recortar(descripcion, 500))
                .usuarioId(userId)
                .publica(Boolean.TRUE.equals(publica))
                .fechaCreacion(LocalDateTime.now())
                .fechaActualizacion(LocalDateTime.now())
                .build();
        p = repo.save(p);
        if (imagen != null && !imagen.isEmpty()) ponerImagen(p, imagen);
        return dto(p, userId, true);
    }

    @Transactional
    public PlaylistDTO editar(Long id, Long userId, Map<String, Object> datos) {
        Playlist p = propia(id, userId);
        if (datos.containsKey("nombre")) p.setNombre(validarNombre(String.valueOf(datos.get("nombre"))));
        if (datos.containsKey("descripcion")) p.setDescripcion(recortar(Objects.toString(datos.get("descripcion"), ""), 500));
        if (datos.containsKey("publica")) p.setPublica(Boolean.parseBoolean(String.valueOf(datos.get("publica"))));
        p.setFechaActualizacion(LocalDateTime.now());
        return dto(repo.save(p), userId, false);
    }

    @Transactional
    public PlaylistDTO cambiarImagen(Long id, Long userId, MultipartFile file) throws IOException {
        Playlist p = propia(id, userId);
        ponerImagen(p, file);
        return dto(p, userId, false);
    }

    @Transactional
    public PlaylistDTO quitarImagen(Long id, Long userId) {
        Playlist p = propia(id, userId);
        borrarImagen(p);
        p.setPortadaUrl(null);
        p.setPortadaPath(null);
        return dto(repo.save(p), userId, false);
    }

    @Transactional
    public void eliminar(Long id, Long userId) {
        Playlist p = propia(id, userId);
        borrarImagen(p);
        repo.vaciar(p.getId());
        repo.delete(p);
    }

    /** Copia una playlist (propia o pública) a las del usuario. */
    @Transactional
    public PlaylistDTO duplicar(Long id, Long userId) {
        PlaylistDTO origen = detalle(id, userId);
        Playlist copia = repo.save(Playlist.builder()
                .nombre(recortar(origen.getNombre() + (origen.isPropia() ? " (copia)" : ""), 250))
                .descripcion(origen.getDescripcion())
                .usuarioId(userId)
                .fechaCreacion(LocalDateTime.now())
                .fechaActualizacion(LocalDateTime.now())
                .build());
        for (CancionDTO c : origen.getCanciones()) repo.insertarCancion(copia.getId(), c.getId());
        actualizarConteo(copia);
        return dto(copia, userId, false);
    }

    // ═══════════════════════ Canciones ═══════════════════════

    @Transactional
    public Map<String, Object> agregar(Long id, Long userId, List<Long> cancionIds) {
        Playlist p = propia(id, userId);
        Set<Long> actuales = new HashSet<>(ids(p.getId()));
        int agregadas = 0, repetidas = 0;
        for (Long cid : cancionIds) {
            if (cid == null || biblioteca.porId(cid).isEmpty()) continue;
            if (actuales.add(cid)) {
                repo.insertarCancion(p.getId(), cid);
                agregadas++;
            } else repetidas++;
        }
        actualizarConteo(p);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("agregadas", agregadas);
        r.put("repetidas", repetidas);
        r.put("playlist", dto(p, userId, false));
        return r;
    }

    @Transactional
    public PlaylistDTO quitar(Long id, Long userId, Long cancionId) {
        Playlist p = propia(id, userId);
        repo.quitarCancion(p.getId(), cancionId);
        actualizarConteo(p);
        return dto(p, userId, false);
    }

    /** Guarda un nuevo orden de canciones (se reescriben las filas en ese orden). */
    @Transactional
    public PlaylistDTO reordenar(Long id, Long userId, List<Long> orden) {
        Playlist p = propia(id, userId);
        Set<Long> actuales = new LinkedHashSet<>(ids(p.getId()));
        List<Long> nuevo = new ArrayList<>();
        for (Long cid : orden) if (actuales.remove(cid)) nuevo.add(cid);
        nuevo.addAll(actuales); // las que no venían en la lista quedan al final
        repo.vaciar(p.getId());
        repo.flush();
        for (Long cid : nuevo) repo.insertarCancion(p.getId(), cid);
        actualizarConteo(p);
        return dto(p, userId, true);
    }

    // ═══════════════════════ Helpers ═══════════════════════

    private Playlist propia(Long id, Long userId) {
        return repo.findByIdAndUsuarioId(id, userId)
                .orElseThrow(() -> new NoSuchElementException("Playlist no encontrada"));
    }

    private List<Long> ids(Long playlistId) {
        return repo.findCancionIds(playlistId).stream().map(Number::longValue).toList();
    }

    private void actualizarConteo(Playlist p) {
        p.setCancionesCount(repo.findCancionIds(p.getId()).size());
        p.setFechaActualizacion(LocalDateTime.now());
        repo.save(p);
    }

    private void ponerImagen(Playlist p, MultipartFile file) throws IOException {
        String nombre = imagenes.guardar(file, SUBCARPETA, "playlist_" + p.getId(), 800);
        borrarImagen(p);
        p.setPortadaUrl("/api/playlists/imagen/" + nombre);
        p.setPortadaPath(nombre);
        p.setFechaActualizacion(LocalDateTime.now());
        repo.save(p);
    }

    private void borrarImagen(Playlist p) {
        String nombre = nombreImagen(p);
        if (nombre != null) imagenes.borrar(SUBCARPETA, nombre);
    }

    /** Nombre del archivo de portada (las antiguas guardaban la ruta completa). */
    private static String nombreImagen(Playlist p) {
        String ref = p.getPortadaPath() != null ? p.getPortadaPath() : p.getPortadaUrl();
        if (ref == null || !ref.contains("playlist_")) return null;
        return ref.substring(Math.max(ref.lastIndexOf('/'), ref.lastIndexOf('\\')) + 1);
    }

    private PlaylistDTO dto(Playlist p, Long userId, boolean conCanciones) {
        List<CancionDTO> canciones = biblioteca.porIds(repo.findCancionIds(p.getId()));
        String portada = null;
        String img = nombreImagen(p);
        if (img != null) portada = "/api/playlists/imagen/" + img; // normaliza URLs antiguas con IP
        else if (p.getPortadaUrl() != null && p.getPortadaUrl().startsWith("http")
                && !p.getPortadaUrl().contains("singlecolorimage")) portada = p.getPortadaUrl();

        boolean propia = Objects.equals(p.getUsuarioId(), userId);
        String autor = null;
        if (!propia && p.getUsuarioId() != null) {
            autor = usuarios.findById(p.getUsuarioId()).map(Usuario::getNombreVisible).orElse(null);
        }
        return PlaylistDTO.builder()
                .id(p.getId())
                .nombre(p.getNombre())
                .descripcion(p.getDescripcion())
                .portadaUrl(portada)
                .portadas(canciones.stream().map(CancionDTO::getPortadaUrl).distinct().limit(4).toList())
                .cancionesCount(canciones.size())
                .duracionTotal(canciones.stream().mapToInt(CancionDTO::getDuracionSegundos).sum())
                .publica(p.isPublica())
                .propia(propia)
                .autor(autor)
                .fechaCreacion(p.getFechaCreacion())
                .fechaActualizacion(p.getFechaActualizacion())
                .canciones(conCanciones ? canciones : null)
                .build();
    }

    private static String validarNombre(String n) {
        if (n == null || n.isBlank() || n.equals("null")) throw new IllegalArgumentException("Escribe un nombre para la playlist");
        return recortar(n, 250);
    }

    private static String recortar(String s, int max) {
        if (s == null) return null;
        s = s.trim();
        return s.length() > max ? s.substring(0, max) : s;
    }
}
