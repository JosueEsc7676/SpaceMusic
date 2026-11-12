package com.bpm.music.experiencias;

import com.bpm.music.dtos.CancionDTO;
import com.bpm.music.models.Usuario;
import com.bpm.music.organizador.SenalesExternasService;
import com.bpm.music.repositories.SeguimientoRepository;
import com.bpm.music.repositories.UsuarioRepository;
import com.bpm.music.services.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * "El punto donde coincidimos": una persona invita a otra (que la sigue o a quien sigue); la otra debe aceptar.
 * Cada una aporta hasta 5 canciones favoritas. La sesión muestra lo que comparten, lo que aporta cada una y
 * "puentes": canciones del servidor cercanas a las dos. Solo las dos personas pueden ver la sesión.
 * No hay escucha sincronizada: cada quien la escucha en su dispositivo.
 */
@Service
public class CoincidimosService {

    private static final List<SesionCoincidencia.Estado> ABIERTAS = List.of(SesionCoincidencia.Estado.INVITADA, SesionCoincidencia.Estado.ACTIVA);
    static final Set<String> REACCIONES = Set.of("REPRESENTA", "SORPRENDIO", "NO_ES_PARA_MI");

    private final SesionCoincidenciaRepository sesiones;
    private final AporteCoincidenciaRepository aportes;
    private final UsuarioRepository usuarios;
    private final SeguimientoRepository seguimientos;
    private final NotificacionesService notificaciones;
    private final BibliotecaService biblioteca;
    private final UsuarioService usuarioService;
    private final EstilosService estilos;
    private final SenalesExternasService senales;
    private final RecomendacionService recomendaciones;

    public CoincidimosService(SesionCoincidenciaRepository sesiones, AporteCoincidenciaRepository aportes, UsuarioRepository usuarios,
                              SeguimientoRepository seguimientos, NotificacionesService notificaciones, BibliotecaService biblioteca,
                              UsuarioService usuarioService, EstilosService estilos, SenalesExternasService senales, RecomendacionService recomendaciones) {
        this.sesiones = sesiones;
        this.aportes = aportes;
        this.usuarios = usuarios;
        this.seguimientos = seguimientos;
        this.notificaciones = notificaciones;
        this.biblioteca = biblioteca;
        this.usuarioService = usuarioService;
        this.estilos = estilos;
        this.senales = senales;
        this.recomendaciones = recomendaciones;
    }

    @Transactional
    public SesionCoincidencia invitar(Long uid, String username) {
        Usuario otro = usuarios.findByUsernameIgnoreCase(username == null ? "" : username.trim().replaceFirst("^@", ""))
                .filter(Usuario::isActivo).orElseThrow(() -> new IllegalArgumentException("No encontramos a esa persona"));
        if (otro.getId().equals(uid)) throw new IllegalArgumentException("Invita a otra persona");
        boolean conocidos = seguimientos.siguiendo(uid).contains(otro.getId()) || seguimientos.seguidores(uid).contains(otro.getId());
        if (!conocidos) throw new IllegalArgumentException("Solo puedes invitar a alguien que sigues o que te sigue");
        boolean yaHay = sesiones.deUsuario(uid, ABIERTAS).stream().anyMatch(s -> s.getCreadorId().equals(otro.getId()) || s.getInvitadoId().equals(otro.getId()));
        if (yaHay) throw new IllegalArgumentException("Ya tienen una sesión abierta");
        SesionCoincidencia s = new SesionCoincidencia();
        s.setCreadorId(uid);
        s.setInvitadoId(otro.getId());
        s.setEstado(SesionCoincidencia.Estado.INVITADA);
        s = sesiones.save(s);
        String quien = usuarios.findById(uid).map(Usuario::getNombreVisible).orElse("Alguien");
        notificaciones.crear(otro.getId(), "COINCIDIMOS", uid, quien + " te invitó a «El punto donde coincidimos». Acepta si quieres participar.", "#/coincidimos/" + s.getId());
        return s;
    }

    private SesionCoincidencia propia(Long id, Long uid) {
        SesionCoincidencia s = sesiones.findById(id).orElseThrow(() -> new NoSuchElementException("Sesión no encontrada"));
        if (!s.getCreadorId().equals(uid) && !s.getInvitadoId().equals(uid)) throw new NoSuchElementException("Sesión no encontrada");
        return s;
    }

    @Transactional
    public SesionCoincidencia responder(Long id, Long uid, boolean acepta) {
        SesionCoincidencia s = propia(id, uid);
        if (!s.getInvitadoId().equals(uid) || s.getEstado() != SesionCoincidencia.Estado.INVITADA) throw new IllegalArgumentException("Esta invitación ya no está pendiente");
        s.setEstado(acepta ? SesionCoincidencia.Estado.ACTIVA : SesionCoincidencia.Estado.RECHAZADA);
        if (acepta) {
            s.setAceptadaEn(LocalDateTime.now());
            String quien = usuarios.findById(uid).map(Usuario::getNombreVisible).orElse("Tu invitado");
            notificaciones.crear(s.getCreadorId(), "COINCIDIMOS", uid, quien + " aceptó: elijan sus canciones y descubran dónde coinciden", "#/coincidimos/" + s.getId());
        }
        return sesiones.save(s);
    }

    @Transactional
    public void cerrar(Long id, Long uid) {
        SesionCoincidencia s = propia(id, uid);
        s.setEstado(SesionCoincidencia.Estado.CERRADA);
        sesiones.save(s);
    }

    @Transactional
    public void aportar(Long id, Long uid, Long cancionId) {
        SesionCoincidencia s = propia(id, uid);
        if (s.getEstado() != SesionCoincidencia.Estado.ACTIVA) throw new IllegalArgumentException("La sesión no está activa");
        biblioteca.porId(cancionId).orElseThrow(() -> new NoSuchElementException("Canción no encontrada"));
        if (aportes.countBySesionIdAndUsuarioIdAndTipo(id, uid, "APORTE") >= 5) throw new IllegalArgumentException("Cada quien aporta hasta 5 canciones");
        aportes.deleteBySesionIdAndUsuarioIdAndCancionIdAndTipoIn(id, uid, cancionId, List.of("APORTE"));
        aportes.save(new AporteCoincidencia(id, uid, cancionId, "APORTE"));
    }

    @Transactional
    public void quitarAporte(Long id, Long uid, Long cancionId) {
        propia(id, uid);
        aportes.deleteBySesionIdAndUsuarioIdAndCancionIdAndTipoIn(id, uid, cancionId, List.of("APORTE"));
    }

    @Transactional
    public void reaccionar(Long id, Long uid, Long cancionId, String reaccion) {
        SesionCoincidencia s = propia(id, uid);
        if (s.getEstado() != SesionCoincidencia.Estado.ACTIVA) throw new IllegalArgumentException("La sesión no está activa");
        if (!REACCIONES.contains(reaccion)) throw new IllegalArgumentException("Reacción no válida");
        aportes.deleteBySesionIdAndUsuarioIdAndCancionIdAndTipoIn(id, uid, cancionId, REACCIONES);
        aportes.save(new AporteCoincidencia(id, uid, cancionId, reaccion));
    }

    public List<Map<String, Object>> mias(Long uid) {
        List<Map<String, Object>> r = new ArrayList<>();
        for (SesionCoincidencia s : sesiones.deUsuario(uid, ABIERTAS)) {
            Long otro = s.getCreadorId().equals(uid) ? s.getInvitadoId() : s.getCreadorId();
            r.add(Map.of("id", s.getId(), "estado", s.getEstado(), "conmigo", persona(otro), "meInvitaron", s.getInvitadoId().equals(uid)));
        }
        return r;
    }

    private Map<String, Object> persona(Long id) {
        return usuarios.findById(id).map(u -> Map.<String, Object>of("username", u.getUsername(), "nombre", u.getNombreVisible(),
                "fotoPerfil", Objects.toString(u.getFotoPerfil(), ""))).orElse(Map.of("username", "", "nombre", "Cuenta eliminada", "fotoPerfil", ""));
    }

    /** La sesión: lo compartido, lo que aportó cada quien y puentes entre los dos. */
    public Map<String, Object> ver(Long id, Long uid) {
        SesionCoincidencia s = propia(id, uid);
        Long otro = s.getCreadorId().equals(uid) ? s.getInvitadoId() : s.getCreadorId();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", s.getId());
        r.put("estado", s.getEstado());
        r.put("meInvitaron", s.getInvitadoId().equals(uid));
        r.put("yo", persona(uid));
        r.put("conmigo", persona(otro));
        if (s.getEstado() != SesionCoincidencia.Estado.ACTIVA) return r;

        List<AporteCoincidencia> todos = aportes.findBySesionId(id);
        List<CancionDTO> miAporte = canciones(todos, uid, "APORTE"), suAporte = canciones(todos, otro, "APORTE");
        Map<Long, Map<String, String>> reacciones = new HashMap<>();
        for (AporteCoincidencia a : todos) if (REACCIONES.contains(a.getTipo()))
            reacciones.computeIfAbsent(a.getCancionId(), k -> new HashMap<>()).put(a.getUsuarioId().equals(uid) ? "yo" : "otro", a.getTipo());
        r.put("miAporte", miAporte);
        r.put("suAporte", suAporte);
        r.put("reacciones", reacciones);

        // Gustos de cada uno: aportes + Me gusta + lo más escuchado (solo para calcular; no se muestra la lista del otro)
        List<CancionDTO> misGustos = gustos(uid, miAporte), susGustos = gustos(otro, suAporte);
        Set<Long> mios = new HashSet<>(), suyos = new HashSet<>();
        misGustos.forEach(c -> mios.add(c.getId()));
        susGustos.forEach(c -> suyos.add(c.getId()));
        List<CancionDTO> compartidas = misGustos.stream().filter(c -> suyos.contains(c.getId())).distinct().limit(15).toList();
        r.put("compartidas", compartidas);
        // Estilos y artistas en común
        Map<String, Integer> eMios = conteoEstilos(misGustos), eSuyos = conteoEstilos(susGustos);
        List<String> enComun = eMios.keySet().stream().filter(eSuyos::containsKey)
                .sorted(Comparator.comparingInt((String k) -> Math.min(eMios.get(k), eSuyos.get(k))).reversed())
                .map(k -> EstilosService.porClave(k).map(EstilosService.Estilo::nombre).orElse(k)).limit(5).toList();
        r.put("estilosEnComun", enComun);
        // Puentes: canciones del servidor que ninguno escuchó y se parecen a gustos de los dos
        var permitidaYo = recomendaciones.permitidas(uid);
        var permitidaOtro = recomendaciones.permitidas(otro);
        Set<Long> escuchadas = new HashSet<>(recomendaciones.escuchadas(uid));
        escuchadas.addAll(recomendaciones.escuchadas(otro));
        Map<Long, double[]> cercania = new HashMap<>(); // id → [a mí, a la otra persona]
        for (CancionDTO c : misGustos) for (Long p : senales.parecidas(c.getId())) cercania.computeIfAbsent(p, k -> new double[2])[0] += 1;
        for (CancionDTO c : susGustos) for (Long p : senales.parecidas(c.getId())) cercania.computeIfAbsent(p, k -> new double[2])[1] += 1;
        List<Map<String, Object>> puentes = new ArrayList<>();
        cercania.entrySet().stream().filter(e -> e.getValue()[0] > 0 && e.getValue()[1] > 0 && !escuchadas.contains(e.getKey()))
                .sorted(Comparator.comparingDouble((Map.Entry<Long, double[]> e) -> Math.min(e.getValue()[0], e.getValue()[1])).reversed())
                .limit(12).forEach(e -> biblioteca.porId(e.getKey()).filter(c -> permitidaYo.test(c) && permitidaOtro.test(c))
                        .ifPresent(c -> puentes.add(Map.of("cancion", c, "razon", "Se parece a canciones de los dos"))));
        if (puentes.size() < 8 && !eMios.isEmpty()) {
            // Sin suficientes relaciones: estilos en común, de artistas que ninguno escucha todavía
            Set<String> claves = new HashSet<>(eMios.keySet());
            claves.retainAll(eSuyos.keySet());
            List<CancionDTO> l = new ArrayList<>(biblioteca.todas().stream().filter(c -> claves.contains(estilos.de(c).clave())
                    && !escuchadas.contains(c.getId()) && permitidaYo.test(c) && permitidaOtro.test(c)).toList());
            Collections.shuffle(l, new Random(id));
            for (CancionDTO c : EstilosService.variadas(l, 1, 8 - puentes.size())) puentes.add(Map.of("cancion", c, "razon", "Del estilo " + estilos.de(c).nombre() + " que comparten"));
        }
        r.put("puentes", puentes);
        return r;
    }

    private List<CancionDTO> canciones(List<AporteCoincidencia> todos, Long uid, String tipo) {
        return todos.stream().filter(a -> a.getUsuarioId().equals(uid) && a.getTipo().equals(tipo))
                .map(a -> biblioteca.porId(a.getCancionId()).orElse(null)).filter(Objects::nonNull).toList();
    }

    private List<CancionDTO> gustos(Long uid, List<CancionDTO> aporte) {
        List<CancionDTO> l = new ArrayList<>(aporte);
        l.addAll(usuarioService.favoritos(uid));
        l.addAll(usuarioService.masEscuchadas(uid, 120, 50));
        return l;
    }

    private Map<String, Integer> conteoEstilos(List<CancionDTO> l) {
        Map<String, Integer> m = new HashMap<>();
        for (CancionDTO c : l) { var e = estilos.de(c); if (e != EstilosService.SIN_ESTILO) m.merge(e.clave(), 1, Integer::sum); }
        return m;
    }
}
