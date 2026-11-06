package com.bpm.music.services;

import com.bpm.music.models.Mensaje;
import com.bpm.music.models.Notificacion;
import com.bpm.music.models.Usuario;
import com.bpm.music.repositories.MensajeRepository;
import com.bpm.music.repositories.NotificacionRepository;
import com.bpm.music.repositories.SeguimientoRepository;
import com.bpm.music.repositories.UsuarioRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.util.*;

/**
 * Mensajes directos (solo entre amigos: los dos se siguen) y notificaciones.
 * Las apps consultan /api/notificaciones/resumen cada pocos segundos para avisar al momento.
 */
@Service
public class MensajesService {

    private final MensajeRepository mensajes;
    private final NotificacionRepository notificaciones;
    private final SeguimientoRepository seguimientos;
    private final UsuarioRepository usuarios;
    private final PerfilService perfiles;
    private final BibliotecaService biblioteca;

    public MensajesService(MensajeRepository mensajes, NotificacionRepository notificaciones, SeguimientoRepository seguimientos,
                           UsuarioRepository usuarios, PerfilService perfiles, BibliotecaService biblioteca) {
        this.mensajes = mensajes;
        this.notificaciones = notificaciones;
        this.seguimientos = seguimientos;
        this.usuarios = usuarios;
        this.perfiles = perfiles;
        this.biblioteca = biblioteca;
    }

    public boolean sonAmigos(Long a, Long b) {
        return seguimientos.existsBySeguidorIdAndSeguidoId(a, b) && seguimientos.existsBySeguidorIdAndSeguidoId(b, a);
    }

    private Usuario porUsername(String username) {
        return usuarios.findByUsernameIgnoreCase(username)
                .filter(u -> u.isActivo() && u.getEstadoCuenta() == Usuario.EstadoCuenta.APROBADA)
                .orElseThrow(() -> new NoSuchElementException("Este usuario no existe"));
    }

    private static String iso(java.time.LocalDateTime f) {
        return f.atZone(ZoneId.systemDefault()).toInstant().toString();
    }

    private Map<String, Object> dto(Mensaje m, Long yo) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", m.getId());
        d.put("mio", m.getDeId().equals(yo));
        d.put("texto", m.getTexto());
        d.put("fecha", iso(m.getFecha()));
        d.put("leido", m.isLeido());
        if (m.getCancionId() != null) biblioteca.porId(m.getCancionId()).ifPresent(c -> d.put("cancion", c));
        return d;
    }

    // ═══════════════════ Mensajes ═══════════════════

    /** Conversaciones del usuario, la más reciente primero, con su último mensaje y los no leídos. */
    public List<Map<String, Object>> conversaciones(Long yo) {
        Map<Long, Long> noLeidos = new HashMap<>();
        for (Object[] r : mensajes.noLeidosPorRemitente(yo)) noLeidos.put(((Number) r[0]).longValue(), ((Number) r[1]).longValue());
        LinkedHashMap<Long, Mensaje> ultimo = new LinkedHashMap<>();
        for (Mensaje m : mensajes.recientes(yo, PageRequest.of(0, 500))) {
            Long otro = m.getDeId().equals(yo) ? m.getParaId() : m.getDeId();
            ultimo.putIfAbsent(otro, m);
        }
        Map<Long, Usuario> us = new HashMap<>();
        usuarios.findAllById(ultimo.keySet()).forEach(u -> us.put(u.getId(), u));
        List<Map<String, Object>> r = new ArrayList<>();
        ultimo.forEach((otro, m) -> {
            Usuario u = us.get(otro);
            if (u == null) return;
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("usuario", perfiles.mini(u, yo));
            c.put("ultimo", dto(m, yo));
            c.put("noLeidos", noLeidos.getOrDefault(otro, 0L));
            c.put("puedeEscribir", sonAmigos(yo, otro));
            r.add(c);
        });
        return r;
    }

    /** Amigos con los que todavía no hay conversación (para empezar una). */
    public List<Map<String, Object>> amigos(Long yo) {
        Set<Long> siguen = new HashSet<>(seguimientos.seguidores(yo));
        List<Map<String, Object>> r = new ArrayList<>();
        for (Usuario u : usuarios.findAllById(seguimientos.siguiendo(yo))) {
            if (siguen.contains(u.getId()) && u.isActivo()) r.add(perfiles.mini(u, yo));
        }
        return r;
    }

    public Map<String, Object> conversacion(Long yo, String username, Long antes) {
        Usuario otro = porUsername(username);
        List<Mensaje> lista = mensajes.conversacion(yo, otro.getId(), antes == null ? Long.MAX_VALUE : antes, PageRequest.of(0, 50));
        mensajes.marcarLeidos(yo, otro.getId());
        List<Map<String, Object>> ms = new ArrayList<>(lista.stream().map(m -> dto(m, yo)).toList());
        Collections.reverse(ms); // del más viejo al más nuevo
        return Map.of("usuario", perfiles.mini(otro, yo), "mensajes", ms,
                "puedeEscribir", sonAmigos(yo, otro.getId()), "hayMas", lista.size() == 50);
    }

    public Map<String, Object> enviar(Long yo, String username, String texto, Long cancionId) {
        Usuario otro = porUsername(username);
        if (!sonAmigos(yo, otro.getId())) {
            throw new IllegalStateException("Solo puedes escribir a tus amigos: tienen que seguirse los dos");
        }
        String t = texto == null ? "" : texto.trim();
        if (t.isEmpty() && cancionId == null) throw new IllegalArgumentException("Escribe un mensaje");
        if (t.length() > 1000) t = t.substring(0, 1000);
        if (cancionId != null && biblioteca.porId(cancionId).isEmpty()) cancionId = null;
        Mensaje m = mensajes.save(Mensaje.builder().deId(yo).paraId(otro.getId())
                .texto(t.isEmpty() ? "🎵" : t).cancionId(cancionId).build());
        return dto(m, yo);
    }

    // ═══════════════════ Notificaciones ═══════════════════

    public void notificar(Long para, String tipo, Long de, String texto, String ruta) {
        notificaciones.save(Notificacion.builder().usuarioId(para).tipo(tipo).deId(de).texto(texto).ruta(ruta).build());
    }

    public List<Map<String, Object>> notificaciones(Long yo) {
        List<Notificacion> l = notificaciones.findTop40ByUsuarioIdOrderByIdDesc(yo);
        Map<Long, Usuario> us = new HashMap<>();
        usuarios.findAllById(l.stream().map(Notificacion::getDeId).filter(Objects::nonNull).toList()).forEach(u -> us.put(u.getId(), u));
        return l.stream().map(n -> {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("id", n.getId());
            d.put("tipo", n.getTipo());
            d.put("texto", n.getTexto());
            d.put("ruta", n.getRuta());
            d.put("fecha", iso(n.getFecha()));
            d.put("leida", n.isLeida());
            Usuario u = n.getDeId() == null ? null : us.get(n.getDeId());
            if (u != null) d.put("de", perfiles.mini(u, null));
            return d;
        }).toList();
    }

    public void marcarLeidas(Long yo) {
        notificaciones.marcarLeidas(yo);
    }

    /** Contadores y lo último sin leer (las apps lo usan para mostrar avisos del sistema). */
    public Map<String, Object> resumen(Long yo) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("mensajes", mensajes.countByParaIdAndLeidoFalse(yo));
        r.put("notificaciones", notificaciones.countByUsuarioIdAndLeidaFalse(yo));
        Mensaje m = mensajes.findFirstByParaIdAndLeidoFalseOrderByIdDesc(yo);
        if (m != null) {
            usuarios.findById(m.getDeId()).ifPresent(u -> r.put("ultimoMensaje", Map.of(
                    "id", m.getId(), "de", u.getNombreVisible(), "username", u.getUsername(),
                    "texto", m.getCancionId() != null && "🎵".equals(m.getTexto()) ? "Te compartió una canción" : m.getTexto())));
        }
        Notificacion n = notificaciones.findFirstByUsuarioIdAndLeidaFalseOrderByIdDesc(yo);
        if (n != null) r.put("ultimaNotificacion", Map.of("id", n.getId(), "texto", n.getTexto(), "ruta", n.getRuta() == null ? "" : n.getRuta()));
        return r;
    }
}
