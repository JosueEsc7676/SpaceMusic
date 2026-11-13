package com.bpm.music.experiencias;

import com.bpm.music.dtos.CancionDTO;
import com.bpm.music.models.Usuario;
import com.bpm.music.repositories.SeguimientoRepository;
import com.bpm.music.repositories.UsuarioRepository;
import com.bpm.music.services.BibliotecaService;
import com.bpm.music.services.LetrasService;
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
    private LetrasService letras;

    @org.springframework.beans.factory.annotation.Autowired
    void setLetras(LetrasService letras) { this.letras = letras; }

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
        return enviar(uid, username, cancionId, segundo, mensaje, false, null, null);
    }

    /** Líneas de la letra de la canción (de la sincronizada si la hay), sin marcas de tiempo. */
    List<String> lineasDe(Long cancionId) {
        Map<String, Object> l = letras.obtener(cancionId);
        String lrc = Objects.toString(l.get("letraSincronizada"), ""), plano = Objects.toString(l.get("letra"), "");
        String base = lrc.isBlank() ? plano : lrc;
        List<String> r = new ArrayList<>();
        for (String linea : base.split("\\r?\\n")) {
            String t = linea.replaceAll("\\[[^\\]]*]|<\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?>", "").replaceAll("\\s{2,}", " ").trim();
            if (!t.isEmpty()) r.add(t);
        }
        return r;
    }

    private static String clave(String linea) {
        return BibliotecaService.normalizar(linea);
    }

    /**
     * Envía una dedicatoria. Si se eligió letra, deben ser líneas seguidas que estén de verdad en la letra
     * de la canción (no se puede escribir texto inventado como si fuera la letra).
     */
    @Transactional
    public Dedicatoria enviar(Long uid, String username, Long cancionId, Integer segundo, String mensaje,
                              boolean completa, List<String> lineas, Integer segundoFin) {
        return enviar(uid, username, cancionId, segundo, mensaje, completa, lineas, segundoFin, null);
    }

    /** Igual, pero con fecha de entrega (por ejemplo un cumpleaños): hasta entonces la otra persona no la ve. */
    @Transactional
    public Dedicatoria enviar(Long uid, String username, Long cancionId, Integer segundo, String mensaje,
                              boolean completa, List<String> lineas, Integer segundoFin, LocalDateTime programadaPara) {
        if (programadaPara != null) {
            if (programadaPara.isBefore(LocalDateTime.now().plusMinutes(1))) programadaPara = null; // ya es hora: se envía ahora
            else if (programadaPara.isAfter(LocalDateTime.now().plusYears(1))) throw new IllegalArgumentException("Puedes programarla hasta con un año de anticipación");
            else if (dedicatorias.countByDeIdAndEntregadaFalse(uid) >= 30) throw new IllegalArgumentException("Ya tienes muchas dedicatorias programadas");
        }
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
        String fragmento = null;
        Integer fin = null;
        if (!completa && lineas != null && !lineas.isEmpty()) {
            if (lineas.size() > 16) throw new IllegalArgumentException("Elige un fragmento más corto (hasta 16 líneas)");
            List<String> letra = lineasDe(cancionId).stream().map(DedicatoriasService::clave).toList();
            List<String> elegidas = lineas.stream().map(x -> x == null ? "" : x.trim()).filter(x -> !x.isEmpty()).toList();
            if (elegidas.isEmpty()) throw new IllegalArgumentException("Elige al menos una línea de la letra");
            List<String> claves = elegidas.stream().map(DedicatoriasService::clave).toList();
            if (java.util.Collections.indexOfSubList(letra, claves) < 0) throw new IllegalArgumentException("El fragmento no coincide con la letra de la canción");
            fragmento = String.join("\n", elegidas);
            if (fragmento.length() > 2000) throw new IllegalArgumentException("Elige un fragmento más corto");
            if (segundoFin != null) fin = Math.max(seg, Math.min(segundoFin, c.getDuracionSegundos()));
        }
        Dedicatoria d = new Dedicatoria();
        d.setDeId(uid);
        d.setParaId(para.getId());
        d.setCancionId(cancionId);
        d.setSegundo(completa ? 0 : seg);
        d.setMensaje(texto.isEmpty() ? null : texto);
        d.setCompleta(completa);
        d.setLetra(fragmento);
        d.setSegundoFin(fin);
        if (programadaPara != null) {
            d.setProgramadaPara(programadaPara);
            d.setEntregada(false);
            return dedicatorias.save(d); // el aviso sale cuando llegue la hora (entregarProgramadas)
        }
        d = dedicatorias.save(d);
        avisar(d, c);
        return d;
    }

    private void avisar(Dedicatoria d, CancionDTO c) {
        Long uid = d.getDeId();
        boolean completa = Boolean.TRUE.equals(d.getCompleta());
        String fragmento = d.getLetra();
        int seg = d.getSegundo();
        String quien = usuarios.findById(uid).map(Usuario::getNombreVisible).orElse("Alguien");
        String como = completa ? " completa" : fragmento != null ? " con un fragmento de la letra" : seg > 0 ? " desde el " + (seg / 60) + ":" + String.format("%02d", seg % 60) : "";
        notificaciones.crear(d.getParaId(), "DEDICATORIA", uid, quien + " te dedicó «" + c.getNombre() + "»" + como, "#/dedicatorias/" + d.getId());
    }

    /** Cada minuto: entrega las dedicatorias programadas cuya hora llegó. */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    @Transactional
    public void entregarProgramadas() {
        for (Dedicatoria d : dedicatorias.findTop200ByEntregadaFalseAndProgramadaParaLessThanEqual(LocalDateTime.now())) {
            d.setEntregada(true);
            d.setFecha(LocalDateTime.now());
            dedicatorias.save(d);
            biblioteca.porId(d.getCancionId()).ifPresent(c -> avisar(d, c));
        }
    }

    @Transactional
    public void cancelarProgramada(Long id, Long uid) {
        Dedicatoria d = dedicatorias.findById(id).filter(x -> x.getDeId().equals(uid) && !x.esEntregada())
                .orElseThrow(() -> new NoSuchElementException("Dedicatoria programada no encontrada"));
        dedicatorias.delete(d);
    }

    private Dedicatoria recibida(Long id, Long uid) {
        Dedicatoria d = dedicatorias.findById(id).orElseThrow(() -> new NoSuchElementException("Dedicatoria no encontrada"));
        if (!d.getParaId().equals(uid) || !d.esEntregada()) throw new NoSuchElementException("Dedicatoria no encontrada");
        return d;
    }

    public Map<String, Object> ver(Long id, Long uid) {
        Dedicatoria d = dedicatorias.findById(id).orElseThrow(() -> new NoSuchElementException("Dedicatoria no encontrada"));
        boolean esDestino = d.getParaId().equals(uid) && d.esEntregada(); // la programada no se ve antes de tiempo
        if (!esDestino && !d.getDeId().equals(uid)) throw new NoSuchElementException("Dedicatoria no encontrada");
        if (esDestino && !d.isVista()) { d.setVista(true); dedicatorias.save(d); }
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

    /**
     * Personas a las que puedes dedicar: las que te siguen (primero las que también sigues).
     * Con texto, filtra por usuario o nombre.
     */
    public List<Map<String, Object>> personas(Long uid, String q) {
        Set<Long> sigo = new HashSet<>(seguimientos.siguiendo(uid));
        String f = q == null ? "" : BibliotecaService.normalizar(q.replaceFirst("^@", ""));
        List<Map<String, Object>> r = new ArrayList<>();
        for (Long id : seguimientos.seguidores(uid)) {
            Usuario u = usuarios.findById(id).filter(Usuario::isActivo).orElse(null);
            if (u == null) continue;
            if (!f.isEmpty() && !BibliotecaService.normalizar(u.getUsername() + " " + u.getNombreVisible()).contains(f)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("username", u.getUsername());
            m.put("nombre", u.getNombreVisible());
            m.put("fotoPerfil", Objects.toString(u.getFotoPerfil(), ""));
            m.put("mutuo", sigo.contains(id));
            r.add(m);
        }
        r.sort(Comparator.comparing((Map<String, Object> m) -> !(Boolean) m.get("mutuo")).thenComparing(m -> ((String) m.get("nombre")).toLowerCase()));
        return r.stream().limit(60).toList();
    }

    public Map<String, Object> listas(Long uid) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("recibidas", dedicatorias.findTop100ByParaIdAndOcultaFalseOrderByFechaDesc(uid).stream().filter(Dedicatoria::esEntregada).map(d -> fila(d, uid)).toList());
        r.put("enviadas", dedicatorias.findTop100ByDeIdOrderByFechaDesc(uid).stream().limit(50).map(d -> fila(d, uid)).toList());
        r.put("bloqueadas", bloqueos.findByUsuarioId(uid).stream().map(b -> usuarios.findById(b.getBloqueadoId()).map(Usuario::getUsername).orElse("")).filter(s -> !s.isEmpty()).toList());
        return r;
    }

    private Map<String, Object> fila(Dedicatoria d, Long uid) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("cancion", biblioteca.porId(d.getCancionId()).orElse(null));
        m.put("segundo", d.getSegundo());
        m.put("segundoFin", d.getSegundoFin());
        m.put("completa", Boolean.TRUE.equals(d.getCompleta()));
        m.put("letra", d.getLetra());
        m.put("mensaje", d.getMensaje());
        m.put("respuesta", d.getRespuesta());
        m.put("fecha", d.getFecha());
        m.put("vista", d.isVista());
        m.put("guardada", d.isGuardada());
        m.put("recibida", d.getParaId().equals(uid));
        // En milisegundos (instante exacto): el teléfono lo muestra en su propia hora
        if (!d.esEntregada() && d.getProgramadaPara() != null)
            m.put("programadaPara", d.getProgramadaPara().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
        Long otro = d.getParaId().equals(uid) ? d.getDeId() : d.getParaId();
        m.put("persona", usuarios.findById(otro).map(u -> Map.of("username", u.getUsername(), "nombre", u.getNombreVisible(), "fotoPerfil", Objects.toString(u.getFotoPerfil(), ""))).orElse(Map.of()));
        return m;
    }
}
