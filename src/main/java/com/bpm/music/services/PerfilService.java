package com.bpm.music.services;

import com.bpm.music.dtos.CancionDTO;
import com.bpm.music.models.Seguimiento;
import com.bpm.music.models.Usuario;
import com.bpm.music.repositories.SeguimientoRepository;
import com.bpm.music.repositories.UsuarioReproduccionRepository;
import com.bpm.music.repositories.UsuarioRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Perfiles públicos: seguir / amigos, playlists públicas, lo más escuchado, géneros,
 * "vibras", lo que está escuchando ahora y la compatibilidad musical entre dos personas.
 * Cada usuario decide qué datos se ven (ver Usuario.ver*()).
 */
@Service
public class PerfilService {

    private static final long AHORA_MS = 15 * 60_000; // "escuchando ahora" dura 15 minutos sin noticias

    private final UsuarioRepository usuarios;
    private final SeguimientoRepository seguimientos;
    private final UsuarioService usuarioService;
    private final PlaylistService playlists;
    private final BibliotecaService biblioteca;
    private final UsuarioReproduccionRepository repros;
    private final com.bpm.music.repositories.NotificacionRepository notificaciones;
    private final EventosService eventos;
    private final CacheTemporal<Long, Map<String, Object>> cacheStats = new CacheTemporal<>(120_000, 500);

    /** Lo que suena ahora en cada cuenta (se avisa desde el reproductor). */
    private record Presencia(Long cancionId, boolean sonando, long t) {}
    private final Map<Long, Presencia> presencia = new ConcurrentHashMap<>();

    public PerfilService(UsuarioRepository usuarios, SeguimientoRepository seguimientos, UsuarioService usuarioService,
                         PlaylistService playlists, BibliotecaService biblioteca, UsuarioReproduccionRepository repros,
                         com.bpm.music.repositories.NotificacionRepository notificaciones, EventosService eventos) {
        this.notificaciones = notificaciones;
        this.eventos = eventos;
        this.usuarios = usuarios;
        this.seguimientos = seguimientos;
        this.usuarioService = usuarioService;
        this.playlists = playlists;
        this.biblioteca = biblioteca;
        this.repros = repros;
    }

    // ═══════════════════ Utilidades ═══════════════════

    private static boolean visible(Usuario u) {
        return u.isActivo() && u.getEstadoCuenta() == Usuario.EstadoCuenta.APROBADA;
    }

    private Usuario porUsername(String username) {
        return usuarios.findByUsernameIgnoreCase(username).filter(PerfilService::visible)
                .orElseThrow(() -> new NoSuchElementException("Este perfil no existe"));
    }

    /** Datos mínimos para listas (buscador, seguidores, amigos). */
    public Map<String, Object> mini(Usuario u, Long viewerId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("username", u.getUsername());
        m.put("nombre", u.getNombreVisible());
        m.put("fotoPerfil", u.getFotoPerfil());
        if (viewerId != null && !viewerId.equals(u.getId())) {
            boolean yoSigo = seguimientos.existsBySeguidorIdAndSeguidoId(viewerId, u.getId());
            boolean meSigue = seguimientos.existsBySeguidorIdAndSeguidoId(u.getId(), viewerId);
            m.put("yoSigo", yoSigo);
            m.put("meSigue", meSigue);
            m.put("amigos", yoSigo && meSigue);
        }
        return m;
    }

    private Map<String, Object> stats(Long uid) {
        return cacheStats.obtener(uid, () -> usuarioService.estadisticas(uid, 90));
    }

    // ═══════════════════ Perfil ═══════════════════

    public Map<String, Object> perfil(String username, Long viewerId) {
        Usuario u = porUsername(username);
        boolean yo = u.getId().equals(viewerId);
        Map<String, Object> p = new LinkedHashMap<>(mini(u, viewerId));
        p.put("esYo", yo);
        p.put("biografia", u.getBiografia());
        p.put("banner", u.getBanner());
        p.put("bannerPos", u.getBannerPos() == null ? 50 : u.getBannerPos());
        p.put("desde", u.getFechaRegistro());
        p.put("seguidores", seguimientos.countBySeguidoId(u.getId()));
        p.put("siguiendo", seguimientos.countBySeguidorId(u.getId()));
        p.put("playlists", playlists.publicasDe(u.getId(), viewerId));

        Map<String, Object> st = stats(u.getId());
        if (yo || u.verCanciones()) {
            p.put("topCanciones", ((List<?>) st.get("topCanciones")).stream().limit(8).toList());
            p.put("topArtistas", ((List<?>) st.get("topArtistas")).stream().limit(8).toList());
        }
        if (yo || u.verGeneros()) p.put("topGeneros", st.get("topGeneros"));
        if (yo || u.verVibras()) p.put("vibras", vibras(st));
        if (yo || u.verActividad()) p.put("actividad", actividad(u.getId()));
        p.put("totalReproducciones", (yo || u.verCanciones()) ? st.get("totalReproducciones") : null);
        if (!yo && viewerId != null && (u.verCanciones() || u.verGeneros())) p.put("compatibilidad", compatibilidad(viewerId, u.getId()));
        if (yo) p.put("privacidad", Map.of("canciones", u.verCanciones(), "generos", u.verGeneros(),
                "vibras", u.verVibras(), "actividad", u.verActividad()));
        return p;
    }

    /** Lo que escucha ahora, o lo último que escuchó (hasta 3 días). */
    public Map<String, Object> actividad(Long uid) {
        Presencia pr = presencia.get(uid);
        if (pr != null && System.currentTimeMillis() - pr.t() < AHORA_MS) {
            Optional<CancionDTO> c = biblioteca.porId(pr.cancionId());
            if (c.isPresent()) return Map.of("ahora", pr.sonando(), "cancion", c.get(), "hace", new Date(pr.t()).toInstant().toString());
        }
        List<Object[]> ult = repros.findUltimas(uid, PageRequest.of(0, 1));
        if (!ult.isEmpty()) {
            LocalDateTime f = (LocalDateTime) ult.get(0)[1];
            if (f.isAfter(LocalDateTime.now().minusDays(3))) {
                Optional<CancionDTO> c = biblioteca.porId(((Number) ult.get(0)[0]).longValue());
                if (c.isPresent()) return Map.of("ahora", false, "cancion", c.get(),
                        "hace", f.atZone(java.time.ZoneId.systemDefault()).toInstant().toString());
            }
        }
        return null;
    }

    public void avisarReproduccion(Long uid, Long cancionId, boolean sonando) {
        if (uid == null) return;
        if (cancionId == null) { presencia.remove(uid); return; }
        presencia.put(uid, new Presencia(cancionId, sonando, System.currentTimeMillis()));
    }

    // ═══════════════════ Vibras ═══════════════════

    private record Vibra(String nombre, String descripcion, String icono) {
        Map<String, Object> mapa() { return Map.of("nombre", nombre, "descripcion", descripcion, "icono", icono); }
    }

    private static final Map<String, Vibra> POR_FAMILIA = Map.ofEntries(
            Map.entry("urbano", new Vibra("Perreo", "Reggaetón y trap latino de principio a fin", "fire")),
            Map.entry("hiphop", new Vibra("Flow", "El rap y el hip hop no fallan", "mic")),
            Map.entry("regional", new Vibra("Puro regional", "Corridos y música regional mexicana", "star")),
            Map.entry("tropical", new Vibra("Bailador", "Salsa, cumbia y bachata para mover los pies", "wave")),
            Map.entry("electronica", new Vibra("Electrónico", "Beats, house y sonidos electrónicos", "wave")),
            Map.entry("rnb", new Vibra("Suave", "R&B y soul para ir con calma", "moon")),
            Map.entry("pop", new Vibra("Pop lover", "Los éxitos pop siempre suenan", "sparkles")),
            Map.entry("rock", new Vibra("Rockero", "Guitarras al frente", "fire")),
            Map.entry("romantica", new Vibra("Romántico", "Baladas y boleros con sentimiento", "heart")),
            Map.entry("cristiana", new Vibra("Espiritual", "Alabanza y música cristiana", "sun")),
            Map.entry("clasica", new Vibra("Clásico", "Jazz, blues y clásica", "disc")));

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> vibras(Map<String, Object> st) {
        List<Vibra> v = new ArrayList<>();
        // Según los géneros que más escucha
        for (Map<String, Object> g : (List<Map<String, Object>>) st.get("topGeneros")) {
            Vibra x = POR_FAMILIA.get(GeneroService.familia((String) g.get("genero")));
            if (x != null && !v.contains(x)) v.add(x);
            if (v.size() >= 2) break;
        }
        // Según la hora a la que escucha
        List<Integer> porHora = (List<Integer>) st.get("porHora");
        int total = porHora.stream().mapToInt(Integer::intValue).sum();
        if (total >= 10) {
            int noche = 0, manana = 0;
            for (int h = 0; h < 24; h++) {
                if (h >= 22 || h <= 4) noche += porHora.get(h);
                if (h >= 5 && h <= 10) manana += porHora.get(h);
            }
            if (noche > total * 0.35) v.add(new Vibra("Noctámbulo", "Su música suena más de noche", "moon"));
            else if (manana > total * 0.35) v.add(new Vibra("Madrugador", "Empieza el día con música", "sun"));
        }
        // Explorador o fiel a un artista
        long repros = ((Number) st.get("totalReproducciones")).longValue();
        int distintas = ((Number) st.get("cancionesDistintas")).intValue();
        List<Map<String, Object>> artistas = (List<Map<String, Object>>) st.get("topArtistas");
        if (repros >= 20 && distintas > repros * 0.7) v.add(new Vibra("Explorador", "Siempre está descubriendo canciones", "compass"));
        else if (!artistas.isEmpty() && repros >= 10 && ((Number) artistas.get(0).get("veces")).longValue() > repros * 0.4) {
            v.add(new Vibra("Fan número uno", "de " + artistas.get(0).get("artista"), "star"));
        }
        int racha = ((Number) st.get("rachaDias")).intValue();
        if (racha >= 5) v.add(new Vibra("Constante", racha + " días seguidos escuchando", "clock"));
        return v.stream().limit(4).map(Vibra::mapa).toList();
    }

    // ═══════════════════ Compatibilidad ═══════════════════

    @SuppressWarnings("unchecked")
    public Map<String, Object> compatibilidad(Long a, Long b) {
        Map<String, Long> pa = usuarioService.pesosArtistas(a), pb = usuarioService.pesosArtistas(b);
        double artistas = coseno(pa, pb);
        Map<String, Long> ga = familias(stats(a)), gb = familias(stats(b));
        double generos = coseno(ga, gb);
        double x = 0.6 * artistas + 0.4 * generos;
        int pct = (int) Math.round(100 * Math.pow(Math.max(0, Math.min(1, x)), 0.6));
        List<String> comunes = pa.keySet().stream().filter(pb::containsKey)
                .sorted(Comparator.comparingLong((String k) -> -(pa.get(k) + pb.get(k)))).limit(5)
                .map(k -> biblioteca.artistas().stream().filter(ar -> BibliotecaService.normalizar(ar.nombre()).equals(k))
                        .map(ar -> ar.nombre()).findFirst().orElse(k))
                .toList();
        return Map.of("porcentaje", pct, "artistasEnComun", comunes);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Long> familias(Map<String, Object> st) {
        Map<String, Long> m = new HashMap<>();
        for (Map<String, Object> g : (List<Map<String, Object>>) st.get("topGeneros")) {
            m.merge(GeneroService.familia((String) g.get("genero")), ((Number) g.get("veces")).longValue(), Long::sum);
        }
        return m;
    }

    private static double coseno(Map<String, Long> a, Map<String, Long> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        double dot = 0, na = 0, nb = 0;
        for (var e : a.entrySet()) { na += e.getValue() * e.getValue(); Long o = b.get(e.getKey()); if (o != null) dot += e.getValue() * o; }
        for (long v : b.values()) nb += v * v;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    // ═══════════════════ Seguir ═══════════════════

    public Map<String, Object> seguir(Long yo, String username) {
        Usuario u = porUsername(username);
        if (u.getId().equals(yo)) throw new IllegalArgumentException("No puedes seguirte a ti mismo");
        if (!seguimientos.existsBySeguidorIdAndSeguidoId(yo, u.getId())) {
            seguimientos.save(Seguimiento.builder().seguidorId(yo).seguidoId(u.getId()).build());
            // Aviso para la persona seguida (y si ya lo seguía, ahora son amigos)
            usuarios.findById(yo).ifPresent(me -> {
                boolean amigos = seguimientos.existsBySeguidorIdAndSeguidoId(u.getId(), yo);
                notificaciones.save(com.bpm.music.models.Notificacion.builder()
                        .usuarioId(u.getId()).deId(yo).tipo(amigos ? "AMIGO" : "SEGUIDOR")
                        .texto(amigos ? me.getNombreVisible() + " te siguió también: ahora son amigos y pueden enviarse mensajes"
                                : me.getNombreVisible() + " empezó a seguirte")
                        .ruta("#/u/" + me.getUsername()).build());
                eventos.enviar(u.getId(), "notificacion", Map.of("ok", true));
            });
        }
        return mini(u, yo);
    }

    public Map<String, Object> dejarDeSeguir(Long yo, String username) {
        Usuario u = porUsername(username);
        seguimientos.deleteBySeguidorIdAndSeguidoId(yo, u.getId());
        return mini(u, yo);
    }

    public List<Map<String, Object>> seguidores(String username, Long viewerId) {
        return lista(seguimientos.seguidores(porUsername(username).getId()), viewerId);
    }

    public List<Map<String, Object>> siguiendo(String username, Long viewerId) {
        return lista(seguimientos.siguiendo(porUsername(username).getId()), viewerId);
    }

    private List<Map<String, Object>> lista(List<Long> ids, Long viewerId) {
        Map<Long, Usuario> m = usuarios.findAllById(ids).stream().filter(PerfilService::visible)
                .collect(Collectors.toMap(Usuario::getId, x -> x));
        return ids.stream().map(m::get).filter(Objects::nonNull).map(u -> mini(u, viewerId)).toList();
    }

    /** Lo que escuchan ahora (o hace poco) las personas que sigues. */
    public List<Map<String, Object>> actividadAmigos(Long yo) {
        List<Map<String, Object>> r = new ArrayList<>();
        for (Usuario u : usuarios.findAllById(seguimientos.siguiendo(yo))) {
            if (!visible(u) || !u.verActividad()) continue;
            Map<String, Object> a = actividad(u.getId());
            if (a == null) continue;
            Map<String, Object> m = new LinkedHashMap<>(a);
            m.put("usuario", mini(u, yo));
            r.add(m);
        }
        r.sort(Comparator.comparing((Map<String, Object> m) -> !(Boolean) m.get("ahora"))
                .thenComparing(m -> (String) m.get("hace"), Comparator.reverseOrder()));
        return r;
    }

    public List<Map<String, Object>> buscar(String q, Long viewerId) {
        String nq = BibliotecaService.normalizar(q);
        if (nq.length() < 2) return List.of();
        return usuarios.findAll().stream().filter(PerfilService::visible)
                .filter(u -> BibliotecaService.normalizar(u.getUsername()).contains(nq)
                        || BibliotecaService.normalizar(u.getNombreVisible()).contains(nq))
                .limit(8).map(u -> mini(u, viewerId)).toList();
    }

    /** Cambia (o quita, con url = null) la foto de portada. Devuelve la anterior para borrarla. */
    public String ponerBanner(Long uid, String url) {
        Usuario u = usuarios.findById(uid).orElseThrow();
        String anterior = u.getBanner();
        u.setBanner(url);
        u.setBannerPos(50);
        usuarios.save(u);
        return anterior;
    }

    public void posicionBanner(Long uid, int pos) {
        Usuario u = usuarios.findById(uid).orElseThrow();
        u.setBannerPos(Math.max(0, Math.min(100, pos)));
        usuarios.save(u);
    }

    public Map<String, Object> guardarPrivacidad(Long uid, Map<String, Object> b) {
        Usuario u = usuarios.findById(uid).orElseThrow();
        if (b.containsKey("canciones")) u.setMostrarCanciones(Boolean.TRUE.equals(b.get("canciones")));
        if (b.containsKey("generos")) u.setMostrarGeneros(Boolean.TRUE.equals(b.get("generos")));
        if (b.containsKey("vibras")) u.setMostrarVibras(Boolean.TRUE.equals(b.get("vibras")));
        if (b.containsKey("actividad")) {
            u.setMostrarActividad(Boolean.TRUE.equals(b.get("actividad")));
            if (!u.verActividad()) presencia.remove(uid);
        }
        if (b.containsKey("biografia")) {
            String bio = String.valueOf(b.get("biografia") == null ? "" : b.get("biografia")).trim();
            u.setBiografia(bio.isEmpty() ? null : bio.substring(0, Math.min(160, bio.length())));
        }
        usuarios.save(u);
        cacheStats.borrar(uid);
        return Map.of("ok", true);
    }
}
