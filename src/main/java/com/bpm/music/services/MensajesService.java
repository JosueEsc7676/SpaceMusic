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
    private final EventosService eventos;
    private final com.bpm.music.repositories.BloqueoMensajesRepository bloqueos;
    private final com.bpm.music.security.CifradoMensajes cifrado;
    private final java.nio.file.Path carpetaAdjuntos;

    public MensajesService(EventosService eventos, MensajeRepository mensajes, NotificacionRepository notificaciones, SeguimientoRepository seguimientos,
                           UsuarioRepository usuarios, PerfilService perfiles, BibliotecaService biblioteca,
                           com.bpm.music.repositories.BloqueoMensajesRepository bloqueos, com.bpm.music.security.CifradoMensajes cifrado,
                           ImagenService imagenes) {
        this.bloqueos = bloqueos;
        this.cifrado = cifrado;
        this.carpetaAdjuntos = imagenes.carpeta("adjuntos");
        this.eventos = eventos;
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
        if (Boolean.TRUE.equals(m.getBorrado())) {
            d.put("borrado", true);
            d.put("texto", "Mensaje eliminado");
            d.put("fecha", iso(m.getFecha()));
            d.put("leido", m.isLeido());
            return d;
        }
        d.put("texto", m.getTexto());
        if (m.getAdjunto() != null) {
            d.put("tipo", m.getTipo());
            d.put("adjuntoUrl", "/api/mensajes/adjunto/" + m.getId());
            d.put("mime", m.getAdjuntoMime());
            if (m.getDuracion() != null) d.put("duracion", m.getDuracion());
        }
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
            c.put("puedeEscribir", puedeEscribir(yo, otro));
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
        if (mensajes.marcarLeidos(yo, otro.getId()) > 0) avisarVisto(yo, otro.getId());
        List<Map<String, Object>> ms = new ArrayList<>(lista.stream()
                .filter(m -> !(m.getParaId().equals(yo) && Boolean.TRUE.equals(m.getOcultoPara())))
                .map(m -> dto(m, yo)).toList());
        Collections.reverse(ms); // del más viejo al más nuevo
        boolean bloqueado = bloqueos.existsByUsuarioIdAndBloqueadoId(yo, otro.getId());
        return Map.of("usuario", perfiles.mini(otro, yo), "mensajes", ms,
                "puedeEscribir", puedeEscribir(yo, otro.getId()), "hayMas", lista.size() == 50, "bloqueado", bloqueado);
    }

    public Map<String, Object> enviar(Long yo, String username, String texto, Long cancionId) {
        Usuario otro = porUsername(username);
        validarEscribir(yo, otro.getId());
        String t = texto == null ? "" : texto.trim();
        if (t.isEmpty() && cancionId == null) throw new IllegalArgumentException("Escribe un mensaje");
        if (t.length() > 1000) t = t.substring(0, 1000);
        if (cancionId != null && biblioteca.porId(cancionId).isEmpty()) cancionId = null;
        Mensaje m = mensajes.save(Mensaje.builder().deId(yo).paraId(otro.getId())
                .texto(t.isEmpty() ? "🎵" : t).cancionId(cancionId).build());
        avisarNuevo(yo, otro, m);
        return dto(m, yo);
    }

    /** Al instante: a quien lo recibe (con su foto de perfil para la notificación) y a los otros dispositivos de quien lo envía. */
    private void avisarNuevo(Long yo, Usuario otro, Mensaje m) {
        Usuario yoU = usuarios.findById(yo).orElseThrow();
        Map<String, Object> aOtro = new LinkedHashMap<>();
        aOtro.put("con", yoU.getUsername());
        aOtro.put("nombre", yoU.getNombreVisible());
        aOtro.put("foto", Objects.toString(yoU.getFotoPerfil(), ""));
        aOtro.put("mensaje", dto(m, otro.getId()));
        eventos.enviar(otro.getId(), "mensaje", aOtro);
        eventos.enviar(yo, "mensaje", Map.of("con", otro.getUsername(), "nombre", otro.getNombreVisible(), "mensaje", dto(m, yo)));
        if (push != null) push.avisar(otro.getId());
    }

    private PushService push;

    /** Avisos al instante en Android (opcional: en las pruebas no hace falta). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPush(PushService push) { this.push = push; }

    // ═══════════════════ Bloqueos, borrar y archivos (imágenes y audios cifrados) ═══════════════════

    private boolean hayBloqueo(Long a, Long b) {
        return bloqueos.existsByUsuarioIdAndBloqueadoId(a, b) || bloqueos.existsByUsuarioIdAndBloqueadoId(b, a);
    }

    private boolean puedeEscribir(Long yo, Long otro) { return sonAmigos(yo, otro) && !hayBloqueo(yo, otro); }

    private void validarEscribir(Long yo, Long otro) {
        if (hayBloqueo(yo, otro)) throw new IllegalStateException("No puedes escribirle a esta persona");
        if (!sonAmigos(yo, otro)) throw new IllegalStateException("Solo puedes escribir a tus amigos: tienen que seguirse los dos");
    }

    /** Bloquea (o desbloquea) a alguien en los mensajes: ninguno puede escribirle al otro. */
    public Map<String, Object> bloquear(Long yo, String username, boolean bloquear) {
        Usuario otro = usuarios.findByUsernameIgnoreCase(username == null ? "" : username.replaceFirst("^@", ""))
                .orElseThrow(() -> new NoSuchElementException("Este usuario no existe"));
        if (otro.getId().equals(yo)) throw new IllegalArgumentException("No puedes bloquearte a ti");
        if (bloquear) { if (!bloqueos.existsByUsuarioIdAndBloqueadoId(yo, otro.getId())) bloqueos.save(new com.bpm.music.models.BloqueoMensajes(yo, otro.getId())); }
        else bloqueos.deleteByUsuarioIdAndBloqueadoId(yo, otro.getId());
        return Map.of("username", otro.getUsername(), "bloqueado", bloquear);
    }

    public List<Map<String, Object>> bloqueados(Long yo) {
        List<Map<String, Object>> r = new ArrayList<>();
        for (var b : bloqueos.findByUsuarioId(yo)) usuarios.findById(b.getBloqueadoId()).ifPresent(u -> r.add(perfiles.mini(u, yo)));
        return r;
    }

    /**
     * Elimina un mensaje: el tuyo se elimina para los dos (y se borra su archivo); el que recibiste solo se quita
     * de tu conversación.
     */
    public void borrarMensaje(Long yo, Long id) {
        Mensaje m = mensajes.findById(id).orElseThrow(() -> new NoSuchElementException("Mensaje no encontrado"));
        if (m.getDeId().equals(yo)) {
            if (m.getAdjunto() != null) {
                try { java.nio.file.Files.deleteIfExists(carpetaAdjuntos.resolve(m.getAdjunto())); } catch (Exception ignored) { }
            }
            m.setBorrado(true);
            m.setTexto("·");
            m.setAdjunto(null);
            m.setCancionId(null);
            mensajes.save(m);
            Map<String, Object> ev = Map.of("id", id);
            eventos.enviar(m.getParaId(), "mensaje-borrado", ev);
            eventos.enviar(yo, "mensaje-borrado", ev);
        } else if (m.getParaId().equals(yo)) {
            m.setOcultoPara(true);
            mensajes.save(m);
            eventos.enviar(yo, "mensaje-borrado", Map.of("id", id));
        } else {
            throw new NoSuchElementException("Mensaje no encontrado");
        }
    }

    static final long MAX_IMAGEN = 12L * 1024 * 1024, MAX_AUDIO = 20L * 1024 * 1024;

    /** Qué es el archivo por sus primeros bytes (no se confía en el nombre ni en lo que diga el teléfono). */
    static String tipoReal(byte[] d) {
        if (d.length < 12) return null;
        if ((d[0] & 0xff) == 0xFF && (d[1] & 0xff) == 0xD8) return "image/jpeg";
        if ((d[0] & 0xff) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') return "image/png";
        if (d[0] == 'G' && d[1] == 'I' && d[2] == 'F') return "image/gif";
        if (d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F' && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P') return "image/webp";
        if (d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F' && d[8] == 'W' && d[9] == 'A' && d[10] == 'V' && d[11] == 'E') return "audio/wav";
        if (d[4] == 'f' && d[5] == 't' && d[6] == 'y' && d[7] == 'p') {
            String marca = new String(d, 8, 4, java.nio.charset.StandardCharsets.US_ASCII);
            if (marca.startsWith("hei") || marca.startsWith("mif1") || marca.startsWith("hev")) return "image/heic";
            return "audio/mp4"; // m4a / aac grabado en el teléfono
        }
        if ((d[0] & 0xff) == 0x1A && (d[1] & 0xff) == 0x45 && (d[2] & 0xff) == 0xDF && (d[3] & 0xff) == 0xA3) return "audio/webm";
        if (d[0] == 'O' && d[1] == 'g' && d[2] == 'g' && d[3] == 'S') return "audio/ogg";
        if (d[0] == 'I' && d[1] == 'D' && d[2] == '3') return "audio/mpeg";
        if ((d[0] & 0xff) == 0xFF && ((d[1] & 0xff) & 0xE0) == 0xE0) return "audio/mpeg";
        return null;
    }

    @org.springframework.beans.factory.annotation.Value("${organizador.ffmpeg:ffmpeg}")
    private String ffmpeg = "ffmpeg";

    /** Convierte un audio a AAC en .m4a con ffmpeg (null si no se pudo: se guarda el original). */
    byte[] aM4a(byte[] datos) {
        java.nio.file.Path entrada = null, salida = null;
        try {
            entrada = java.nio.file.Files.createTempFile("voz-", ".in");
            salida = java.nio.file.Files.createTempFile("voz-", ".m4a");
            java.nio.file.Files.write(entrada, datos);
            Process p = new ProcessBuilder(ffmpeg, "-nostdin", "-loglevel", "error", "-y", "-i", entrada.toString(),
                    "-vn", "-ac", "1", "-c:a", "aac", "-b:a", "64k", "-movflags", "+faststart", salida.toString())
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) { p.destroyForcibly(); return null; }
            if (p.exitValue() != 0) return null;
            byte[] r = java.nio.file.Files.readAllBytes(salida);
            return r.length > 0 ? r : null;
        } catch (Exception e) {
            return null;
        } finally {
            try { if (entrada != null) java.nio.file.Files.deleteIfExists(entrada); } catch (Exception ignored) { }
            try { if (salida != null) java.nio.file.Files.deleteIfExists(salida); } catch (Exception ignored) { }
        }
    }

    /** Envía una imagen o un audio: se guarda cifrado (AES-256-GCM) y solo los dos lo pueden abrir. */
    public Map<String, Object> enviarArchivo(Long yo, String username, byte[] datos, Integer duracion, String texto) {
        Usuario otro = porUsername(username);
        validarEscribir(yo, otro.getId());
        if (datos == null || datos.length == 0) throw new IllegalArgumentException("No se recibió el archivo");
        String mime = tipoReal(datos);
        if (mime == null) throw new IllegalArgumentException("Solo se pueden enviar fotos (JPG, PNG, WebP, GIF, HEIC) o audios");
        boolean imagen = mime.startsWith("image/");
        if (datos.length > (imagen ? MAX_IMAGEN : MAX_AUDIO))
            throw new IllegalArgumentException(imagen ? "La foto no puede pasar de 12 MB" : "El audio no puede pasar de 20 MB");
        if (!imagen && !"audio/mp4".equals(mime) && !"audio/mpeg".equals(mime)) {
            // Lo grabado en el navegador (WebM/Ogg) no suena en el iPhone: se pasa a AAC (.m4a), que suena en todos
            byte[] m4a = aM4a(datos);
            if (m4a != null) { datos = m4a; mime = "audio/mp4"; }
        }
        String nombre = UUID.randomUUID() + ".bin";
        try {
            java.nio.file.Files.write(carpetaAdjuntos.resolve(nombre), cifrado.cifrarBytes(datos));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("No se pudo guardar el archivo");
        }
        String t = texto == null ? "" : texto.trim();
        if (t.length() > 1000) t = t.substring(0, 1000);
        Mensaje m = mensajes.save(Mensaje.builder().deId(yo).paraId(otro.getId())
                .texto(t.isEmpty() ? (imagen ? "📷 Foto" : "🎤 Mensaje de voz") : t)
                .tipo(imagen ? "IMAGEN" : "AUDIO").adjunto(nombre).adjuntoMime(mime)
                .duracion(imagen || duracion == null ? null : Math.max(0, Math.min(duracion, 3600))).build());
        avisarNuevo(yo, otro, m);
        return dto(m, yo);
    }

    /** El archivo de un mensaje, descifrado, solo para quien lo envió o lo recibió. */
    public Map.Entry<String, byte[]> archivo(Long yo, Long id) {
        Mensaje m = mensajes.findById(id).orElseThrow(() -> new NoSuchElementException("Archivo no encontrado"));
        boolean mio = m.getDeId().equals(yo), paraMi = m.getParaId().equals(yo);
        if ((!mio && !paraMi) || m.getAdjunto() == null || Boolean.TRUE.equals(m.getBorrado())
                || (paraMi && Boolean.TRUE.equals(m.getOcultoPara()))) throw new NoSuchElementException("Archivo no encontrado");
        try {
            byte[] datos = cifrado.descifrarBytes(java.nio.file.Files.readAllBytes(carpetaAdjuntos.resolve(m.getAdjunto())));
            return Map.entry(m.getAdjuntoMime() == null ? "application/octet-stream" : m.getAdjuntoMime(), datos);
        } catch (java.io.IOException e) {
            throw new NoSuchElementException("Archivo no encontrado");
        }
    }

    /** Marca como leídos los mensajes de una conversación (cuando llegan con el chat abierto). */
    public void marcarLeidos(Long yo, String username) {
        Usuario otro = porUsername(username);
        if (mensajes.marcarLeidos(yo, otro.getId()) > 0) avisarVisto(yo, otro.getId());
    }

    private void avisarVisto(Long yo, Long otro) {
        usuarios.findById(yo).ifPresent(u -> eventos.enviar(otro, "visto", Map.of("por", u.getUsername())));
    }

    // ═══════════════════ Notificaciones ═══════════════════

    public void notificar(Long para, String tipo, Long de, String texto, String ruta) {
        notificaciones.save(Notificacion.builder().usuarioId(para).tipo(tipo).deId(de).texto(texto).ruta(ruta).build());
    }

    public List<Map<String, Object>> notificaciones(Long yo) {
        List<Notificacion> l = notificaciones.findTop100ByUsuarioIdOrderByIdDesc(yo);
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

    public void vaciar(Long yo) {
        notificaciones.deleteByUsuarioId(yo);
    }

    public void borrar(Long yo, Long id) {
        notificaciones.deleteByIdAndUsuarioId(id, yo);
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
                    "foto", Objects.toString(u.getFotoPerfil(), ""),
                    "texto", m.getCancionId() != null && "🎵".equals(m.getTexto()) ? "Te compartió una canción" : m.getTexto())));
        }
        Notificacion n = notificaciones.findFirstByUsuarioIdAndLeidaFalseOrderByIdDesc(yo);
        if (n != null) r.put("ultimaNotificacion", Map.of("id", n.getId(), "texto", n.getTexto(), "ruta", n.getRuta() == null ? "" : n.getRuta()));
        return r;
    }
}
