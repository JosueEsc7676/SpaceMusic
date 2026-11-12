package com.bpm.music.experiencias;

import com.bpm.music.dtos.CancionDTO;
import com.bpm.music.models.Usuario;
import com.bpm.music.repositories.SeguimientoRepository;
import com.bpm.music.repositories.UsuarioRepository;
import com.bpm.music.services.BibliotecaService;
import com.bpm.music.services.NotificacionesService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Dedicatorias: "una canción para este momento". Se envían solo cuando la persona pulsa Enviar (nunca solas).
 * Privacidad: solo a alguien que te sigue (así un desconocido no puede escribirte), que no te haya bloqueado
 * las dedicatorias. El destinatario la abre en el segundo elegido, responde y la puede guardar.
 */
@Service
public class DedicatoriasService {

    private final DedicatoriaRepository dedicatorias;
    private final BloqueoDedicatoriaRepository bloqueos;
    private final UsuarioRepository usuarios;
    private final SeguimientoRepository seguimientos;
    private final NotificacionesService notificaciones;
    private final BibliotecaService biblioteca;

    public DedicatoriasService(DedicatoriaRepository dedicatorias, BloqueoDedicatoriaRepository bloqueos, UsuarioRepository usuarios,
                               SeguimientoRepository seguimientos, NotificacionesService notificaciones, BibliotecaService biblioteca) {
        this.dedicatorias = dedicatorias;
        this.bloqueos = bloqueos;
        this.usuarios = usuarios;
        this.seguimientos = seguimientos;
        this.notificaciones = notificaciones;
        this.biblioteca = biblioteca;
    }

    @Transactional
    public Dedicatoria enviar(Long uid, String username, Long cancionId, Integer segundo, String mensaje) {
        Usuario para = usuarios.findByUsernameIgnoreCase(username == null ? "" : username.trim().replaceFirst("^@", ""))
                .filter(Usuario::isActivo).orElseThrow(() -> new IllegalArgumentException("No encontramos a esa persona"));
        if (para.getId().equals(uid)) throw new IllegalArgumentException("Dedícala a otra persona");
        // Solo a quien te sigue, y si no te bloqueó las dedicatorias (la respuesta no dice cuál de las dos falló)
        boolean teSigue = seguimientos.seguidores(uid).contains(para.getId());
        if (!teSigue || bloqueos.existsByUsuarioIdAndBloqueadoId(para.getId(), uid))
            throw new IllegalArgumentException("Solo puedes dedicar canciones a personas que te siguen");
        if (dedicatorias.countByDeIdAndFechaAfter(uid, LocalDateTime.now().minusDays(1)) >= 20)
            throw new IllegalArgumentException("Ya enviaste muchas dedicatorias hoy");
        CancionDTO c = biblioteca.porId(cancionId).orElseThrow(() -> new NoSuchElementException("Canción no encontrada"));
        int seg = segundo == null ? 0 : Math.max(0, Math.min(segundo, Math.max(0, c.getDuracionSegundos() - 5)));
        String texto = mensaje == null ? "" : mensaje.replaceAll("\\p{Cntrl}", " ").trim();
        if (texto.length() > 280) throw new IllegalArgumentException("El mensaje puede tener hasta 280 caracteres");
        Dedicatoria d = new Dedicatoria();
        d.setDeId(uid);
        d.setParaId(para.getId());
        d.setCancionId(cancionId);
        d.setSegundo(seg);
        d.setMensaje(texto.isEmpty() ? null : texto);
        d = dedicatorias.save(d);
        String quien = usuarios.findById(uid).map(Usuario::getNombreVisible).orElse("Alguien");
        notificaciones.crear(para.getId(), "DEDICATORIA", uid, quien + " te dedicó «" + c.getNombre() + "»" + (seg > 0 ? " desde el " + (seg / 60) + ":" + String.format("%02d", seg % 60) : ""), "#/dedicatorias/" + d.getId());
        return d;
    }

    private Dedicatoria recibida(Long id, Long uid) {
        Dedicatoria d = dedicatorias.findById(id).orElseThrow(() -> new NoSuchElementException("Dedicatoria no encontrada"));
        if (!d.getParaId().equals(uid)) throw new NoSuchElementException("Dedicatoria no encontrada");
        return d;
    }

    public Map<String, Object> ver(Long id, Long uid) {
        Dedicatoria d = dedicatorias.findById(id).orElseThrow(() -> new NoSuchElementException("Dedicatoria no encontrada"));
        if (!d.getParaId().equals(uid) && !d.getDeId().equals(uid)) throw new NoSuchElementException("Dedicatoria no encontrada");
        if (d.getParaId().equals(uid) && !d.isVista()) { d.setVista(true); dedicatorias.save(d); }
        return fila(d, uid);
    }

    @Transactional
    public Map<String, Object> responder(Long id, Long uid, String respuesta) {
        Dedicatoria d = recibida(id, uid);
        String t = respuesta == null ? "" : respuesta.replaceAll("\\p{Cntrl}", " ").trim();
        if (t.isEmpty() || t.length() > 280) throw new IllegalArgumentException("Escribe una respuesta de hasta 280 caracteres");
        d.setRespuesta(t);
        d.setRespondidaEn(LocalDateTime.now());
        dedicatorias.save(d);
        String quien = usuarios.findById(uid).map(Usuario::getNombreVisible).orElse("Alguien");
        String cancion = biblioteca.porId(d.getCancionId()).map(CancionDTO::getNombre).orElse("tu canción");
        notificaciones.crear(d.getDeId(), "DEDICATORIA", uid, quien + " respondió a tu dedicatoria de «" + cancion + "»", "#/dedicatorias/" + d.getId());
        return fila(d, uid);
    }

    @Transactional
    public Map<String, Object> guardar(Long id, Long uid, boolean guardar) {
        Dedicatoria d = recibida(id, uid);
        d.setGuardada(guardar);
        return fila(dedicatorias.save(d), uid);
    }

    @Transactional
    public void ocultar(Long id, Long uid) {
        Dedicatoria d = recibida(id, uid);
        d.setOculta(true);
        dedicatorias.save(d);
    }

    @Transactional
    public void bloquear(Long uid, String username, boolean bloquear) {
        Usuario otro = usuarios.findByUsernameIgnoreCase(username == null ? "" : username.replaceFirst("^@", ""))
                .orElseThrow(() -> new IllegalArgumentException("No encontramos a esa persona"));
        if (bloquear) { if (!bloqueos.existsByUsuarioIdAndBloqueadoId(uid, otro.getId())) bloqueos.save(new BloqueoDedicatoria(uid, otro.getId())); }
        else bloqueos.deleteByUsuarioIdAndBloqueadoId(uid, otro.getId());
    }

    public Map<String, Object> listas(Long uid) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("recibidas", dedicatorias.findTop100ByParaIdAndOcultaFalseOrderByFechaDesc(uid).stream().map(d -> fila(d, uid)).toList());
        r.put("enviadas", dedicatorias.findTop100ByDeIdOrderByFechaDesc(uid).stream().limit(50).map(d -> fila(d, uid)).toList());
        r.put("bloqueadas", bloqueos.findByUsuarioId(uid).stream().map(b -> usuarios.findById(b.getBloqueadoId()).map(Usuario::getUsername).orElse("")).filter(s -> !s.isEmpty()).toList());
        return r;
    }

    private Map<String, Object> fila(Dedicatoria d, Long uid) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("cancion", biblioteca.porId(d.getCancionId()).orElse(null));
        m.put("segundo", d.getSegundo());
        m.put("mensaje", d.getMensaje());
        m.put("respuesta", d.getRespuesta());
        m.put("fecha", d.getFecha());
        m.put("vista", d.isVista());
        m.put("guardada", d.isGuardada());
        m.put("recibida", d.getParaId().equals(uid));
        Long otro = d.getParaId().equals(uid) ? d.getDeId() : d.getParaId();
        m.put("persona", usuarios.findById(otro).map(u -> Map.of("username", u.getUsername(), "nombre", u.getNombreVisible(), "fotoPerfil", Objects.toString(u.getFotoPerfil(), ""))).orElse(Map.of()));
        return m;
    }
}
