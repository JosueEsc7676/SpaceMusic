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
 * SyncPlay: escuchar juntos en vivo, con el control compartido.
 *
 * <ul>
 *   <li>El <b>servidor guarda el estado</b> de cada SyncPlay (canción, segundo con la hora del servidor, pausa, cola,
 *       historial y versión). Todos los participantes lo siguen: nadie depende del reproductor de otro.</li>
 *   <li><b>Todos controlan</b>: reproducir, pausar, poner otra canción, anterior/siguiente y mover la posición
 *       ({@link #control}). «Siguiente» con la canción de origen es idempotente: si dos personas lo tocan a la vez,
 *       se avanza una sola vez.</li>
 *   <li>El <b>servidor pasa de canción solo</b> cuando termina la actual (sigue con la cola).</li>
 *   <li>Cada aviso lleva {@code ts} (hora del servidor en la que vale {@code segundo}) y {@code version}: las apps
 *       calculan la posición exacta con su reloj sincronizado y descartan avisos atrasados.</li>
 * </ul>
 *
 * Quien lo inicia (anfitrión) invita y puede cerrarlo. Vive en memoria y se cierra tras 30 min sin actividad.
 * Los avisos van por la conexión en vivo (evento «juntos»). Las apps anteriores siguen funcionando con {@link #estado}.
 */
@Service
public class JuntosService {

    static final int MAX_PERSONAS = 8;
    private static final long INACTIVA_MS = 30 * 60_000L;
    private static final int MAX_COLA = 50, MAX_HISTORIAL = 50;
    /** Reacciones de las apps anteriores (la interfaz nueva ya no las muestra). */
    private static final Set<String> REACCIONES = Set.of("❤️", "🔥", "😂", "😮", "😢", "👏", "🎶", "💃");
    static final Set<String> ACCIONES = Set.of("play", "pause", "seek", "next", "prev", "poner");

    static final class Sala {
        final long id;
        final long anfitrion;
        /** Código para unirse (como Spotify Jam): 6 letras y números fáciles de dictar. */
        final String codigo;
        final Set<Long> dentro = ConcurrentHashMap.newKeySet();
        final Set<Long> invitados = ConcurrentHashMap.newKeySet();
        final List<long[]> cola = new ArrayList<>(); // [cancionId, quién]
        final Deque<Long> historial = new ArrayDeque<>();
        final AtomicLong version = new AtomicLong();
        Long cancionId;
        double segundo, duracion;
        boolean sonando;
        long marca = System.currentTimeMillis(); // hora del servidor en la que vale «segundo»
        volatile long actividad = System.currentTimeMillis();
        Map<String, Object> ultimaAccion;
        Sala(long id, long anfitrion, String codigo) { this.id = id; this.anfitrion = anfitrion; this.codigo = codigo; dentro.add(anfitrion); }

        /** Segundo actual (si está sonando, avanza con el reloj del servidor). */
        synchronized double segundoAhora() {
            double s = sonando ? segundo + (System.currentTimeMillis() - marca) / 1000.0 : segundo;
            return duracion > 0 ? Math.min(s, duracion) : s;
        }
    }

    private final Map<Long, Sala> salas = new ConcurrentHashMap<>();
    /** Sin letras ni números que se confunden (0/O, 1/I/L). */
    private static final String ALFABETO = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private final java.security.SecureRandom azar = new java.security.SecureRandom();
    /** Intentos de código por persona en los últimos minutos (para que no se pueda adivinar probando). */
    private final Map<Long, Deque<Long>> intentos = new ConcurrentHashMap<>();
    static final int MAX_INTENTOS = 10;
    static final long VENTANA_INTENTOS_MS = 10 * 60_000L;

    private String codigoNuevo() {
        for (;;) {
            StringBuilder b = new StringBuilder(6);
            for (int i = 0; i < 6; i++) b.append(ALFABETO.charAt(azar.nextInt(ALFABETO.length())));
            String c = b.toString();
            if (salas.values().stream().noneMatch(s -> s.codigo.equals(c))) return c;
        }
    }

    private Sala nueva(long uid) {
        Sala s = new Sala(secuencia.incrementAndGet(), uid, codigoNuevo());
        salas.put(s.id, s);
        return s;
    }

    /**
     * Unirse con el código (como Spotify Jam): no hace falta invitación. Hasta 10 intentos cada 10 minutos por persona.
     * Espacios, guiones y minúsculas no importan.
     */
    public Map<String, Object> unirseConCodigo(long uid, String codigo) {
        long ahora = System.currentTimeMillis();
        Deque<Long> mios = intentos.computeIfAbsent(uid, k -> new ArrayDeque<>());
        synchronized (mios) {
            while (!mios.isEmpty() && ahora - mios.peekFirst() > VENTANA_INTENTOS_MS) mios.pollFirst();
            if (mios.size() >= MAX_INTENTOS) throw new IllegalArgumentException("Demasiados intentos. Espera unos minutos y vuelve a probar");
            mios.addLast(ahora);
        }
        String c = codigo == null ? "" : codigo.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (c.length() != 6) throw new IllegalArgumentException("El código tiene 6 letras y números");
        Sala s = salas.values().stream().filter(x -> x.codigo.equals(c)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No hay ningún SyncPlay con ese código"));
        if (s.dentro.size() >= MAX_PERSONAS && !s.dentro.contains(uid)) throw new IllegalArgumentException("Este SyncPlay está lleno");
        // Estar en otro SyncPlay a la vez no tiene sentido: se sale del anterior
        for (Sala otra : salas.values()) if (otra != s && otra.dentro.contains(uid)) salir(otra.id, uid);
        s.invitados.remove(uid);
        s.dentro.add(uid);
        s.actividad = ahora;
        synchronized (mios) { mios.clear(); }
        avisar(s);
        return ver(s.id, uid);
    }
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
        if (s == null || (!s.dentro.contains(uid) && !s.invitados.contains(uid))) throw new NoSuchElementException("Este SyncPlay ya terminó");
        return s;
    }

    private Sala dentro(long id, long uid) {
        Sala s = sala(id, uid);
        if (!s.dentro.contains(uid)) throw new IllegalArgumentException("Primero únete a este SyncPlay");
        s.actividad = System.currentTimeMillis();
        return s;
    }

    private String nombre(long uid) { return usuarios.findById(uid).map(Usuario::getNombreVisible).orElse("Alguien"); }

    /** Crea un SyncPlay (o usa el que ya tiene abierto) e invita a alguien que te sigue. */
    public Map<String, Object> invitar(long uid, String username) {
        Usuario otro = usuarios.findByUsernameIgnoreCase(username == null ? "" : username.trim().replaceFirst("^@", ""))
                .filter(Usuario::isActivo).orElseThrow(() -> new IllegalArgumentException("No encontramos a esa persona"));
        if (otro.getId().equals(uid)) throw new IllegalArgumentException("Invita a otra persona");
        if (!seguimientos.seguidores(uid).contains(otro.getId()))
            throw new IllegalArgumentException("Solo puedes invitar a personas que te siguen");
        Sala s = salas.values().stream().filter(x -> x.anfitrion == uid).findFirst().orElse(null);
        if (s == null) {
            if (salas.size() > 200) throw new IllegalStateException("Hay demasiados SyncPlay abiertos; inténtalo en un rato");
            s = nueva(uid);
        }
        if (s.dentro.size() + s.invitados.size() >= MAX_PERSONAS) throw new IllegalArgumentException("Este SyncPlay está lleno (máximo " + MAX_PERSONAS + ")");
        if (!s.dentro.contains(otro.getId())) s.invitados.add(otro.getId());
        notificaciones.crear(otro.getId(), "JUNTOS", uid, nombre(uid) + " te invita a SyncPlay: escuchar música juntos, en vivo", "#/juntos/" + s.id);
        avisar(s);
        return ver(s.id, uid);
    }

    /** SyncPlay propio nuevo sin invitar a nadie todavía. */
    public Map<String, Object> abrir(long uid) {
        Sala s = salas.values().stream().filter(x -> x.anfitrion == uid).findFirst().orElse(null);
        if (s == null) {
            if (salas.size() > 200) throw new IllegalStateException("Hay demasiados SyncPlay abiertos; inténtalo en un rato");
            s = nueva(uid);
        }
        return ver(s.id, uid);
    }

    public Map<String, Object> unirse(long id, long uid) {
        Sala s = sala(id, uid);
        if (s.dentro.size() >= MAX_PERSONAS && !s.dentro.contains(uid)) throw new IllegalArgumentException("Este SyncPlay está lleno");
        s.invitados.remove(uid);
        s.dentro.add(uid);
        s.actividad = System.currentTimeMillis();
        avisar(s);
        return ver(id, uid);
    }

    public void salir(long id, long uid) {
        Sala s = salas.get(id);
        if (s == null) return;
        if (s.anfitrion == uid) { cerrar(s, "Quien inició el SyncPlay lo cerró"); return; }
        s.dentro.remove(uid);
        s.invitados.remove(uid);
        avisar(s);
    }

    // ═══════════════════ Control compartido ═══════════════════

    private void ponerCancion(Sala s, long cancionId, double segundo, boolean guardarHistorial) {
        CancionDTO c = biblioteca.porId(cancionId).orElseThrow(() -> new NoSuchElementException("Canción no encontrada"));
        if (guardarHistorial && s.cancionId != null && s.cancionId != cancionId) {
            s.historial.push(s.cancionId);
            while (s.historial.size() > MAX_HISTORIAL) s.historial.removeLast();
        }
        s.cola.removeIf(x -> x[0] == cancionId); // si estaba en la cola, sale de ahí
        s.cancionId = cancionId;
        s.duracion = Math.max(0, c.getDuracionSegundos());
        s.segundo = Math.max(0, s.duracion > 0 ? Math.min(segundo, s.duracion) : segundo);
        s.sonando = true;
        s.marca = System.currentTimeMillis();
    }

    /** Pasa a la siguiente de la cola; sin cola, se detiene al final de la canción. */
    private void avanzar(Sala s) {
        if (!s.cola.isEmpty()) {
            ponerCancion(s, s.cola.get(0)[0], 0, true);
        } else {
            s.segundo = s.duracion;
            s.sonando = false;
            s.marca = System.currentTimeMillis();
        }
    }

    /**
     * Cualquier participante controla la música.
     * play · pause · seek {segundo} · next {desde: id de la canción que estaba sonando} · prev · poner {cancionId, segundo, cola: [ids]}
     */
    public Map<String, Object> control(long id, long uid, String accion, Map<String, Object> datos) {
        if (!ACCIONES.contains(accion)) throw new IllegalArgumentException("Orden no válida");
        Sala s = dentro(id, uid);
        synchronized (s) {
            switch (accion) {
                case "play" -> {
                    if (s.cancionId == null) {
                        if (s.cola.isEmpty()) throw new IllegalArgumentException("Primero elige una canción");
                        ponerCancion(s, s.cola.get(0)[0], 0, true);
                    } else {
                        if (s.duracion > 0 && s.segundoAhora() >= s.duracion - 0.5) s.segundo = 0; // terminó: empieza de nuevo
                        else s.segundo = s.segundoAhora();
                        s.sonando = true;
                        s.marca = System.currentTimeMillis();
                    }
                }
                case "pause" -> { s.segundo = s.segundoAhora(); s.sonando = false; s.marca = System.currentTimeMillis(); }
                case "seek" -> {
                    if (s.cancionId == null) throw new IllegalArgumentException("No hay canción");
                    double seg = numero(datos.get("segundo"));
                    s.segundo = Math.max(0, s.duracion > 0 ? Math.min(seg, s.duracion) : seg);
                    s.marca = System.currentTimeMillis();
                }
                case "next" -> {
                    Long desde = id(datos.get("desde"));
                    if (desde != null && !desde.equals(s.cancionId)) return ver(id, uid); // ya avanzó otra persona
                    avanzar(s);
                }
                case "prev" -> {
                    if (s.segundoAhora() > 3 || s.historial.isEmpty()) { s.segundo = 0; s.marca = System.currentTimeMillis(); }
                    else ponerCancion(s, s.historial.pop(), 0, false);
                }
                case "poner" -> {
                    Long c = id(datos.get("cancionId"));
                    if (c == null) throw new IllegalArgumentException("Elige una canción");
                    ponerCancion(s, c, numero(datos.get("segundo")), true);
                    if (datos.get("cola") instanceof List<?> l) {
                        for (Object x : l) {
                            Long y = id(x);
                            if (y == null || s.cola.size() >= MAX_COLA || y.equals(s.cancionId) || s.cola.stream().anyMatch(q -> q[0] == y)) continue;
                            if (biblioteca.porId(y).isPresent()) s.cola.add(new long[]{y, uid});
                        }
                    }
                }
                default -> { }
            }
            s.ultimaAccion = Map.of("accion", accion, "nombre", nombre(uid), "username",
                    usuarios.findById(uid).map(Usuario::getUsername).orElse(""));
            s.version.incrementAndGet();
        }
        avisar(s);
        return ver(id, uid);
    }

    /** Apps anteriores: el anfitrión informaba su reproductor. Ahora lo puede informar cualquier participante. */
    public void estado(long id, long uid, Long cancionId, double segundo, boolean sonando) {
        Sala s = dentro(id, uid);
        synchronized (s) {
            if (cancionId != null && !cancionId.equals(s.cancionId)) ponerCancion(s, cancionId, segundo, true);
            else if (cancionId == null) { s.cancionId = null; s.duracion = 0; s.segundo = 0; }
            else s.segundo = Math.max(0, segundo);
            s.sonando = sonando && s.cancionId != null;
            s.marca = System.currentTimeMillis();
            s.version.incrementAndGet();
        }
        avisar(s);
    }

    private static double numero(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        try { return o == null ? 0 : Double.parseDouble(String.valueOf(o)); } catch (NumberFormatException e) { return 0; }
    }

    private static Long id(Object o) {
        if (o instanceof Number n) return n.longValue();
        try { return o == null || String.valueOf(o).isBlank() ? null : Long.valueOf(String.valueOf(o)); } catch (NumberFormatException e) { return null; }
    }

    /** Cada medio segundo: los SyncPlay cuya canción terminó pasan a la siguiente de la cola. */
    @Scheduled(fixedDelay = 500)
    public void avanzarTerminadas() {
        for (Sala s : salas.values()) {
            boolean cambio = false;
            synchronized (s) {
                if (s.sonando && s.cancionId != null && s.duracion > 0 && s.segundoAhora() >= s.duracion - 0.25) {
                    avanzar(s);
                    s.ultimaAccion = Map.of("accion", "fin", "nombre", "", "username", "");
                    s.version.incrementAndGet();
                    cambio = true;
                }
            }
            if (cambio) avisar(s);
        }
    }

    // ═══════════════════ Cola ═══════════════════

    public void sumarACola(long id, long uid, long cancionId) {
        Sala s = dentro(id, uid);
        if (biblioteca.porId(cancionId).isEmpty()) throw new NoSuchElementException("Canción no encontrada");
        synchronized (s) {
            if (s.cola.size() >= MAX_COLA) throw new IllegalArgumentException("La cola está llena");
            if (s.cola.stream().noneMatch(c -> c[0] == cancionId)) s.cola.add(new long[]{cancionId, uid});
            s.version.incrementAndGet();
        }
        avisar(s);
    }

    /** Cualquier participante puede quitar canciones de la cola (el control es compartido). */
    public void quitarDeCola(long id, long uid, long cancionId) {
        Sala s = dentro(id, uid);
        synchronized (s) { s.cola.removeIf(c -> c[0] == cancionId); s.version.incrementAndGet(); }
        avisar(s);
    }

    /** Apps anteriores: reacciones (la interfaz nueva ya no las muestra). */
    public void reaccionar(long id, long uid, String emoji) {
        Sala s = dentro(id, uid);
        if (!REACCIONES.contains(emoji)) throw new IllegalArgumentException("Reacción no válida");
        String nombre = nombre(uid);
        for (Long p : s.dentro) eventos.enviar(p, "juntos-reaccion", Map.of("sala", s.id, "emoji", emoji, "nombre", nombre, "mia", p == uid));
    }

    // ═══════════════════ Estado ═══════════════════

    public Map<String, Object> ver(long id, long uid) {
        Sala s = sala(id, uid);
        Map<String, Object> r = new LinkedHashMap<>();
        synchronized (s) {
            long ahora = System.currentTimeMillis();
            r.put("id", s.id);
            r.put("version", s.version.get());
            r.put("soyAnfitrion", s.anfitrion == uid);
            r.put("dentro", s.dentro.contains(uid));
            // El código solo lo ven quienes ya están dentro (para compartirlo)
            if (s.dentro.contains(uid)) r.put("codigo", s.codigo);
            r.put("anfitrion", persona(s.anfitrion));
            r.put("personas", s.dentro.stream().map(this::persona).filter(Objects::nonNull).toList());
            r.put("invitados", s.invitados.stream().map(this::persona).filter(Objects::nonNull).toList());
            r.put("cancion", s.cancionId == null ? null : biblioteca.porId(s.cancionId).orElse(null));
            // «segundo» vale exactamente en «ts» (hora del servidor): la app suma lo transcurrido si está sonando
            r.put("segundo", Math.round(s.segundoAhora() * 1000) / 1000.0);
            r.put("ts", ahora);
            r.put("ahora", ahora);
            r.put("sonando", s.sonando);
            r.put("hayAnterior", !s.historial.isEmpty());
            List<Map<String, Object>> cola = new ArrayList<>();
            for (long[] c : s.cola) {
                CancionDTO dto = biblioteca.porId(c[0]).orElse(null);
                if (dto == null) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("cancion", dto);
                m.put("de", persona(c[1]));
                cola.add(m);
            }
            r.put("cola", cola);
            if (s.ultimaAccion != null) r.put("ultimaAccion", s.ultimaAccion);
        }
        return r;
    }

    /** Los SyncPlay en los que estás o a los que te invitaron. */
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

    /** SyncPlay sin actividad por 30 minutos: se cierran. */
    @Scheduled(fixedDelay = 60_000)
    public void limpiar() {
        long limite = System.currentTimeMillis() - INACTIVA_MS;
        for (Sala s : List.copyOf(salas.values())) {
            if (s.actividad < limite && !s.sonando) cerrar(s, "El SyncPlay se cerró por inactividad");
        }
    }

    /** Para pruebas. */
    int cuantas() { return salas.size(); }
}
