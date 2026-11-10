package com.bpm.music.services;

import com.bpm.music.dtos.CancionDTO;
import com.bpm.music.dtos.PlaylistDTO;
import com.bpm.music.models.Playlist;
import com.bpm.music.models.Usuario;
import com.bpm.music.models.PlaylistColaborador;
import com.bpm.music.repositories.PlaylistColaboradorRepository;
import com.bpm.music.repositories.PlaylistRepository;
import com.bpm.music.repositories.SeguimientoRepository;
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

    private final NotificacionesService notificaciones;
    private PlaylistColaboradorRepository colaboradores;
    private SeguimientoRepository seguimientos;

    @org.springframework.beans.factory.annotation.Autowired
    void setColaboracion(PlaylistColaboradorRepository colaboradores, SeguimientoRepository seguimientos) {
        this.colaboradores = colaboradores;
        this.seguimientos = seguimientos;
    }

    public PlaylistService(PlaylistRepository repo, UsuarioRepository usuarios,
                           BibliotecaService biblioteca, ImagenService imagenes, NotificacionesService notificaciones) {
        this.notificaciones = notificaciones;
        this.repo = repo;
        this.usuarios = usuarios;
        this.biblioteca = biblioteca;
        this.imagenes = imagenes;
    }

    // ═══════════════════════ Consultas ═══════════════════════

    /** Las del usuario y, después, las colaborativas a las que lo invitaron. */
    public List<PlaylistDTO> mias(Long userId) {
        List<PlaylistDTO> r = new ArrayList<>(repo.findByUsuarioIdOrderByFechaCreacionDesc(userId).stream()
                .map(p -> dto(p, userId, false)).toList());
        for (Playlist p : repo.findAllById(colaboradores.playlistsDe(userId))) r.add(dto(p, userId, false));
        return r;
    }

    public List<PlaylistDTO> publicas(Long userId) {
        return repo.findPublicasDeOtros(userId).stream()
                .map(p -> dto(p, userId, false))
                .filter(p -> p.getCancionesCount() > 0)
                .toList();
    }

    public PlaylistDTO detalle(Long id, Long userId) {
        Playlist p = repo.findById(id).orElseThrow(() -> new NoSuchElementException("Playlist no encontrada"));
        if (!Objects.equals(p.getUsuarioId(), userId) && !p.isPublica() && !colaboradores.existsByPlaylistIdAndUsuarioId(id, userId)) {
            throw new NoSuchElementException("Playlist no encontrada");
        }
        return dto(p, userId, true);
    }

    /** Playlists públicas de un usuario (para su perfil). Las privadas nunca se muestran. */
    public List<PlaylistDTO> publicasDe(Long duenoId, Long viewerId) {
        return repo.findByUsuarioIdOrderByFechaCreacionDesc(duenoId).stream()
                .filter(Playlist::isPublica)
                .map(p -> dto(p, viewerId, false))
                .filter(p -> p.getCancionesCount() > 0)
                .toList();
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
        boolean eraPublica = p.isPublica();
        if (datos.containsKey("publica")) p.setPublica(Boolean.parseBoolean(String.valueOf(datos.get("publica"))));
        p.setFechaActualizacion(LocalDateTime.now());
        PlaylistDTO r = dto(repo.save(p), userId, false);
        if (!eraPublica && p.isPublica() && r.getCancionesCount() > 0) notificaciones.playlistPublicada(userId, p.getId(), p.getNombre());
        return r;
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
        colaboradores.quitarTodos(p.getId());
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
        Playlist p = editable(id, userId);
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
        // Una playlist pública que estaba vacía ya tiene canciones: ahora sí se avisa a los seguidores
        if (p.isPublica() && agregadas > 0 && actuales.size() == agregadas) notificaciones.playlistPublicada(p.getUsuarioId(), p.getId(), p.getNombre());
        if (agregadas > 0 && !Objects.equals(p.getUsuarioId(), userId)) avisarCambio(p, userId, agregadas);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("agregadas", agregadas);
        r.put("repetidas", repetidas);
        r.put("playlist", dto(p, userId, false));
        return r;
    }

    @Transactional
    public PlaylistDTO quitar(Long id, Long userId, Long cancionId) {
        Playlist p = editable(id, userId);
        repo.quitarCancion(p.getId(), cancionId);
        actualizarConteo(p);
        return dto(p, userId, false);
    }

    /** Guarda un nuevo orden de canciones (se reescriben las filas en ese orden). */
    @Transactional
    public PlaylistDTO reordenar(Long id, Long userId, List<Long> orden) {
        Playlist p = editable(id, userId);
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

    /** Dueño o invitado: pueden cambiar las canciones (no el nombre, la portada ni la visibilidad). */
    private Playlist editable(Long id, Long userId) {
        Playlist p = repo.findById(id).orElseThrow(() -> new NoSuchElementException("Playlist no encontrada"));
        if (Objects.equals(p.getUsuarioId(), userId) || colaboradores.existsByPlaylistIdAndUsuarioId(id, userId)) return p;
        throw new NoSuchElementException("Playlist no encontrada");
    }

    // ═══════════════════════ Playlists colaborativas ═══════════════════════

    private static final int MAX_COLABORADORES = 20;

    /** El dueño invita a alguien que sigue o que lo sigue (amigos), por su usuario. */
    @Transactional
    public PlaylistDTO invitar(Long id, Long duenoId, String username) {
        Playlist p = propia(id, duenoId);
        String nombre = username == null ? "" : username.trim().replaceFirst("^@", "");
        Usuario u = usuarios.findByUsernameIgnoreCase(nombre)
                .filter(Usuario::isActivo)
                .orElseThrow(() -> new IllegalArgumentException("No encontramos a esa persona"));
        if (Objects.equals(u.getId(), duenoId)) throw new IllegalArgumentException("Ya eres el dueño de esta playlist");
        boolean amigos = seguimientos.siguiendo(duenoId).contains(u.getId()) || seguimientos.seguidores(duenoId).contains(u.getId());
        if (!amigos) throw new IllegalArgumentException("Solo puedes invitar a personas que sigues o que te siguen");
        if (colaboradores.existsByPlaylistIdAndUsuarioId(id, u.getId())) throw new IllegalArgumentException("@" + u.getUsername() + " ya colabora en esta playlist");
        if (colaboradores.countByPlaylistId(id) >= MAX_COLABORADORES) throw new IllegalArgumentException("Esta playlist ya tiene " + MAX_COLABORADORES + " colaboradores");
        colaboradores.save(new PlaylistColaborador(id, u.getId()));
        String dueno = usuarios.findById(duenoId).map(Usuario::getNombreVisible).orElse("Alguien");
        notificaciones.crear(u.getId(), "PLAYLIST", duenoId, dueno + " te invitó a colaborar en “" + p.getNombre() + "”", "#/playlist/" + id);
        return dto(p, duenoId, true);
    }

    /** El dueño quita a un invitado, o el invitado sale de la playlist. */
    @Transactional
    public void quitarColaborador(Long id, Long userId, Long colaboradorId) {
        Playlist p = repo.findById(id).orElseThrow(() -> new NoSuchElementException("Playlist no encontrada"));
        boolean dueno = Objects.equals(p.getUsuarioId(), userId);
        if (!dueno && !Objects.equals(userId, colaboradorId)) throw new NoSuchElementException("Playlist no encontrada");
        if (colaboradores.quitar(id, colaboradorId) == 0) throw new NoSuchElementException("Esa persona no colabora en esta playlist");
    }

    private void avisarCambio(Playlist p, Long autorId, int cuantas) {
        String quien = usuarios.findById(autorId).map(Usuario::getNombreVisible).orElse("Un colaborador");
        notificaciones.crear(p.getUsuarioId(), "PLAYLIST", autorId,
                quien + " agregó " + cuantas + (cuantas == 1 ? " canción" : " canciones") + " a “" + p.getNombre() + "”", "#/playlist/" + p.getId());
    }

    /** Ids de las canciones de una playlist (para saber de qué estilo es). */
    public List<Long> idsCanciones(Long playlistId) {
        return ids(playlistId);
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
        String autor = null, autorUsername = null;
        if (!propia && p.getUsuarioId() != null) {
            Optional<Usuario> u = usuarios.findById(p.getUsuarioId());
            autor = u.map(Usuario::getNombreVisible).orElse(null);
            autorUsername = u.map(Usuario::getUsername).orElse(null);
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
                .autorUsername(autorUsername)
                .fechaCreacion(p.getFechaCreacion())
                .fechaActualizacion(p.getFechaActualizacion())
                .canciones(conCanciones ? canciones : null)
                .colaborativa(colaboradores.countByPlaylistId(p.getId()) > 0)
                .editable(propia || colaboradores.existsByPlaylistIdAndUsuarioId(p.getId(), userId))
                .colaboradores(conCanciones ? listaColaboradores(p.getId()) : null)
                .build();
    }

    private List<Map<String, Object>> listaColaboradores(Long playlistId) {
        List<Map<String, Object>> r = new ArrayList<>();
        for (PlaylistColaborador c : colaboradores.findByPlaylistIdOrderByFechaAsc(playlistId)) {
            usuarios.findById(c.getUsuarioId()).ifPresent(u -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", u.getId());
                m.put("username", u.getUsername());
                m.put("nombre", u.getNombreVisible());
                m.put("fotoPerfil", u.getFotoPerfil());
                r.add(m);
            });
        }
        return r;
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
