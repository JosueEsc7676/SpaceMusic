package com.bpm.music.experiencias;

import com.bpm.music.dtos.CancionDTO;
import com.bpm.music.models.Usuario;
import com.bpm.music.repositories.SeguimientoRepository;
import com.bpm.music.repositories.UsuarioRepository;
import com.bpm.music.services.BibliotecaService;
import com.bpm.music.services.EventosService;
import com.bpm.music.services.NotificacionesService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Escuchar juntos en vivo: quien crea la sala (anfitrión) controla la música y todos escuchan la misma canción
 * en el mismo segundo. Cualquiera en la sala puede sumar canciones a la cola y reaccionar.
 * Las salas viven en memoria (se pierden al reiniciar el servidor) y se cierran solas tras 30 min sin actividad.
 * Los cambios llegan al instante por la conexión en vivo (/api/eventos, evento "juntos").
 */
@Service
public class JuntosService {

    static final int MAX_PERSONAS = 8;
    private static final long INACTIVA_MS = 30 * 60_000L;
    private static final Set<String> REACCIONES = Set.of("❤️", "🔥", "😂", "😮", "😢", "👏", "🎶", "💃");

    static final class Sala {
        final long id;
        final long anfitrion;
        final Set<Long> dentro = ConcurrentHashMap.newKeySet();
        final Set<Long> invitados = ConcurrentHashMap.newKeySet();
        final List<long[]> cola = Collections.synchronizedList(new ArrayList<>()); // [cancionId, quién]
        volatile Long cancionId;
        volatile double segundo;
        volatile boolean sonando;
        volatile long marca = System.currentTimeMillis(); // cuándo se fijó "segundo"
        volatile long actividad = System.currentTimeMillis();
        Sala(long id, long anfitrion) { this.id = id; this.anfitrion = anfitrion; dentro.add(anfitrion); }

        /** Segundo actual según el último estado del anfitrión (si está sonando, avanza con el reloj). */
        double segundoAhora() {
            return sonando ? segundo + (System.currentTimeMillis() - marca) / 1000.0 : segundo;
        }
    }

    private final Map<Long, Sala> salas = new ConcurrentHashMap<>();
    private final AtomicLong secuencia = new AtomicLong(System.currentTimeMillis() % 100_000);
    private final UsuarioRepository usuarios;
    private final SeguimientoRepository seguimientos;
    private final NotificacionesService notificaciones;
    private final EventosService eventos;
    private final BibliotecaService biblioteca;

    public JuntosService(UsuarioRepository usuarios, SeguimientoRepository seguimientos, NotificacionesService notificaciones,
                         EventosService eventos, BibliotecaService biblioteca) {
        this.usuarios = usuarios;
        this.seguimientos = seguimientos;
        this.notificaciones = notificaciones;
        this.eventos = eventos;
        this.biblioteca = biblioteca;
    }

    private Sala sala(long id, long uid) {
        Sala s = salas.get(id);
        if (s == null || (!s.dentro.contains(uid) && !s.invitados.contains(uid))) throw new NoSuchElementException("La sala ya terminó");
        return s;
    }

    private Sala dentro(long id, long uid) {
        Sala s = sala(id, uid);
        if (!s.dentro.contains(uid)) throw new IllegalArgumentException("Primero únete a la sala");
        s.actividad = System.currentTimeMillis();
        return s;
    }

    /** Crea una sala (o usa la que ya tiene abierta) e invita a alguien que te sigue. */
    public Map<String, Object> invitar(long uid, String username) {
        Usuario otro = usuarios.findByUsernameIgnoreCase(username == null ? "" : username.trim().replaceFirst("^@", ""))
                .filter(Usuario::isActivo).orElseThrow(() -> new IllegalArgumentException("No encontramos a esa persona"));
        if (otro.getId().equals(uid)) throw new IllegalArgumentException("Invita a otra persona");
        if (!seguimientos.seguidores(uid).contains(otro.getId()))
            throw new IllegalArgumentException("Solo puedes invitar a personas que te siguen");
        Sala s = salas.values().stream().filter(x -> x.anfitrion == uid).findFirst().orElse(null);
        if (s == null) {
            if (salas.size() > 200) throw new IllegalStateException("Hay demasiadas salas abiertas; inténtalo en un rato");
            s = new Sala(secuencia.incrementAndGet(), uid);
            salas.put(s.id, s);
        }
        if (s.dentro.size() + s.invitados.size() >= MAX_PERSONAS) throw new IllegalArgumentException("La sala está llena (máximo " + MAX_PERSONAS + ")");
        if (!s.dentro.contains(otro.getId())) s.invitados.add(otro.getId());
        String quien = usuarios.findById(uid).map(Usuario::getNombreVisible).orElse("Alguien");
        notificaciones.crear(otro.getId(), "JUNTOS", uid, quien + " te invita a SyncPlay: escuchar música juntos, en vivo", "#/juntos/" + s.id);
        avisar(s);
        return ver(s.id, uid);
    }

    /** Sala propia nueva sin invitar a nadie todavía. */
    public Map<String, Object> abrir(long uid) {
        Sala s = salas.values().stream().filter(x -> x.anfitrion == uid).findFirst().orElse(null);
        if (s == null) { s = new Sala(secuencia.incrementAndGet(), uid); salas.put(s.id, s); }
        return ver(s.id, uid);
    }

    public Map<String, Object> unirse(long id, long uid) {
        Sala s = sala(id, uid);
        if (s.dentro.size() >= MAX_PERSONAS && !s.dentro.contains(uid)) throw new IllegalArgumentException("La sala está llena");
        s.invitados.remove(uid);
        s.dentro.add(uid);
        s.actividad = System.currentTimeMillis();
        avisar(s);
        return ver(id, uid);
    }

    public void salir(long id, long uid) {
        Sala s = salas.get(id);
        if (s == null) return;
        if (s.anfitrion == uid) { cerrar(s, "El anfitrión cerró la sala"); return; }
        s.dentro.remove(uid);
        s.invitados.remove(uid);
        avisar(s);
    }

    /** Solo el anfitrión: qué suena, en qué segundo y si está en pausa. */
    public void estado(long id, long uid, Long cancionId, double segundo, boolean sonando) {
        Sala s = dentro(id, uid);
        if (s.anfitrion != uid) throw new IllegalArgumentException("Solo quien abrió la sala controla la música");
        if (cancionId != null && biblioteca.porId(cancionId).isEmpty()) throw new NoSuchElementException("Canción no encontrada");
        // Si la canción que empieza estaba en la cola, se quita de ahí
        if (cancionId != null && !cancionId.equals(s.cancionId)) synchronized (s.cola) { s.cola.removeIf(c -> c[0] == cancionId); }
        s.cancionId = cancionId;
        s.segundo = Math.max(0, segundo);
        s.sonando = sonando && cancionId != null;
        s.marca = System.currentTimeMillis();
        avisar(s);
    }

    public void sumarACola(long id, long uid, long cancionId) {
        Sala s = dentro(id, uid);
        if (biblioteca.porId(cancionId).isEmpty()) throw new NoSuchElementException("Canción no encontrada");
        synchronized (s.cola) {
            if (s.cola.size() >= 50) throw new IllegalArgumentException("La cola está llena");
            if (s.cola.stream().noneMatch(c -> c[0] == cancionId)) s.cola.add(new long[]{cancionId, uid});
        }
        avisar(s);
    }

    public void quitarDeCola(long id, long uid, long cancionId) {
        Sala s = dentro(id, uid);
        synchronized (s.cola) { s.cola.removeIf(c -> c[0] == cancionId && (c[1] == uid || s.anfitrion == uid)); }
        avisar(s);
    }

    public void reaccionar(long id, long uid, String emoji) {
        Sala s = dentro(id, uid);
        if (!REACCIONES.contains(emoji)) throw new IllegalArgumentException("Reacción no válida");
        String nombre = usuarios.findById(uid).map(Usuario::getNombreVisible).orElse("");
        for (Long p : s.dentro) eventos.enviar(p, "juntos-reaccion", Map.of("sala", s.id, "emoji", emoji, "nombre", nombre, "mia", p == uid));
    }

    public Map<String, Object> ver(long id, long uid) {
        Sala s = sala(id, uid);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", s.id);
        r.put("soyAnfitrion", s.anfitrion == uid);
        r.put("dentro", s.dentro.contains(uid));
        r.put("anfitrion", persona(s.anfitrion));
        r.put("personas", s.dentro.stream().map(this::persona).filter(Objects::nonNull).toList());
        r.put("invitados", s.invitados.stream().map(this::persona).filter(Objects::nonNull).toList());
        r.put("cancion", s.cancionId == null ? null : biblioteca.porId(s.cancionId).orElse(null));
        r.put("segundo", Math.round(s.segundoAhora() * 10) / 10.0);
        r.put("sonando", s.sonando);
        r.put("ahora", System.currentTimeMillis());
        List<Map<String, Object>> cola = new ArrayList<>();
        synchronized (s.cola) {
            for (long[] c : s.cola) {
                CancionDTO dto = biblioteca.porId(c[0]).orElse(null);
                if (dto == null) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("cancion", dto);
                m.put("de", persona(c[1]));
                cola.add(m);
            }
        }
        r.put("cola", cola);
        r.put("reacciones", REACCIONES.stream().sorted().toList());
        return r;
    }

    /** Las salas en las que estás o a las que te invitaron. */
    public List<Map<String, Object>> mias(long uid) {
        return salas.values().stream().filter(s -> s.dentro.contains(uid) || s.invitados.contains(uid))
                .map(s -> ver(s.id, uid)).toList();
    }

    private Map<String, Object> persona(Long uid) {
        return usuarios.findById(uid).map(u -> Map.<String, Object>of("username", u.getUsername(), "nombre", u.getNombreVisible(),
                "fotoPerfil", Objects.toString(u.getFotoPerfil(), ""))).orElse(null);
    }

    private void avisar(Sala s) {
        Set<Long> todos = new HashSet<>(s.dentro);
        todos.addAll(s.invitados);
        for (Long p : todos) {
            try { eventos.enviar(p, "juntos", ver(s.id, p)); } catch (RuntimeException ignored) { }
        }
    }

    private void cerrar(Sala s, String motivo) {
        salas.remove(s.id);
        Set<Long> todos = new HashSet<>(s.dentro);
        todos.addAll(s.invitados);
        for (Long p : todos) eventos.enviar(p, "juntos-fin", Map.of("sala", s.id, "motivo", motivo));
    }

    /** Salas sin actividad por 30 minutos: se cierran. */
    @Scheduled(fixedDelay = 60_000)
    public void limpiar() {
        long limite = System.currentTimeMillis() - INACTIVA_MS;
        for (Sala s : List.copyOf(salas.values())) {
            if (s.actividad < limite) cerrar(s, "La sala se cerró por inactividad");
        }
    }

    /** Para pruebas. */
    int cuantas() { return salas.size(); }
}
