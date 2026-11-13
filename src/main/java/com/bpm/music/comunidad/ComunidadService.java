package com.bpm.music.comunidad;

import com.bpm.music.dtos.CancionDTO;
import com.bpm.music.models.Usuario;
import com.bpm.music.repositories.UsuarioRepository;
import com.bpm.music.services.BibliotecaService;
import com.bpm.music.services.NotificacionesService;
import com.bpm.music.soporte.Sanitizador;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.*;
import java.time.format.TextStyle;
import java.util.*;
import java.util.function.Consumer;

/**
 * Funciones para los usuarios: gustos al crear la cuenta, reportar problemas
 * y el resumen del mes o del año ("Tu año en SpaceMusic").
 */
@Service
public class ComunidadService {

    private static final Locale ES = Locale.forLanguageTag("es");
    public static final Set<String> MOTIVOS = Set.of("audio", "letra", "datos", "portada", "duplicada", "otro");
    private static final Map<String, String> MOTIVO_TEXTO = Map.of("audio", "no suena bien", "letra", "la letra está mal",
            "datos", "datos incorrectos", "portada", "portada incorrecta", "duplicada", "está repetida", "otro", "otro problema");

    private final UsuarioGustoRepository gustos;
    private final ReporteCancionRepository reportes;
    private final UsuarioRepository usuarios;
    private final BibliotecaService biblioteca;
    private final NotificacionesService notificaciones;
    private final JdbcTemplate jdbc;

    /** Se avisa al cambiar los gustos (para recalcular recomendaciones). */
    public static final List<Consumer<Long>> alCambiarGustos = new java.util.concurrent.CopyOnWriteArrayList<>();

    public ComunidadService(UsuarioGustoRepository gustos, ReporteCancionRepository reportes,
                            UsuarioRepository usuarios, BibliotecaService biblioteca, NotificacionesService notificaciones, JdbcTemplate jdbc) {
        this.gustos = gustos;
        this.reportes = reportes;
        this.usuarios = usuarios;
        this.biblioteca = biblioteca;
        this.notificaciones = notificaciones;
        this.jdbc = jdbc;
    }

    // ═══════════════════ Gustos (primer inicio) ═══════════════════

    public Map<String, Object> gustos(Long uid) {
        List<UsuarioGusto> l = gustos.findByUsuarioId(uid);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("hecho", l.stream().anyMatch(g -> g.getTipo() == UsuarioGusto.Tipo.HECHO));
        r.put("generos", l.stream().filter(g -> g.getTipo() == UsuarioGusto.Tipo.GENERO).map(UsuarioGusto::getValor).toList());
        r.put("artistas", l.stream().filter(g -> g.getTipo() == UsuarioGusto.Tipo.ARTISTA).map(UsuarioGusto::getValor).toList());
        return r;
    }

    /** Opciones para elegir: géneros y artistas que hay en la biblioteca (con portada). */
    public Map<String, Object> opciones() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("generos", biblioteca.generos().stream().limit(40).toList());
        r.put("artistas", biblioteca.artistas().stream().filter(a -> a.totalCanciones() >= 2).limit(60).toList());
        return r;
    }

    /** Guarda lo elegido (reemplaza lo anterior). Solo se aceptan géneros y artistas que existen. */
    @Transactional
    public Map<String, Object> guardarGustos(Long uid, List<String> generos, List<String> artistas) {
        Set<String> generosOk = new HashSet<>();
        for (Map<String, Object> g : biblioteca.generos()) generosOk.add(String.valueOf(g.get("nombre")));
        Set<String> artistasOk = new HashSet<>();
        biblioteca.artistas().forEach(a -> artistasOk.add(a.nombre()));
        gustos.borrarDe(uid);
        List<UsuarioGusto> nuevos = new ArrayList<>();
        nuevos.add(new UsuarioGusto(uid, UsuarioGusto.Tipo.HECHO, "1"));
        if (generos != null) generos.stream().filter(generosOk::contains).distinct().limit(15)
                .forEach(g -> nuevos.add(new UsuarioGusto(uid, UsuarioGusto.Tipo.GENERO, g)));
        if (artistas != null) artistas.stream().filter(artistasOk::contains).distinct().limit(30)
                .forEach(a -> nuevos.add(new UsuarioGusto(uid, UsuarioGusto.Tipo.ARTISTA, a)));
        gustos.saveAll(nuevos);
        alCambiarGustos.forEach(f -> f.accept(uid));
        return gustos(uid);
    }

    /** Géneros elegidos, normalizados (para las recomendaciones). */
    public Set<String> generosElegidos(Long uid) {
        Set<String> r = new HashSet<>();
        for (UsuarioGusto g : gustos.findByUsuarioId(uid))
            if (g.getTipo() == UsuarioGusto.Tipo.GENERO) r.add(BibliotecaService.normalizar(g.getValor()));
        return r;
    }

    /** Artistas elegidos, normalizados. */
    public Set<String> artistasElegidos(Long uid) {
        Set<String> r = new HashSet<>();
        for (UsuarioGusto g : gustos.findByUsuarioId(uid))
            if (g.getTipo() == UsuarioGusto.Tipo.ARTISTA) r.add(BibliotecaService.normalizar(g.getValor()));
        return r;
    }

    // ═══════════════════ Reportar un problema ═══════════════════

    @Transactional
    public Map<String, Object> reportar(Long uid, Long cancionId, String motivo, String detalle, String cliente) {
        if (cancionId == null) throw new IllegalArgumentException("Falta la canción");
        CancionDTO c = biblioteca.porId(cancionId).orElseThrow(() -> new NoSuchElementException("Canción no encontrada"));
        if (motivo == null || !MOTIVOS.contains(motivo)) throw new IllegalArgumentException("Elige qué problema tiene");
        if (reportes.countByUsuarioIdAndFechaAfter(uid, LocalDateTime.now().minusDays(1)) >= 20)
            throw new IllegalArgumentException("Ya enviaste muchos reportes hoy. Gracias por ayudar; inténtalo mañana.");
        Map<String, Object> r = new LinkedHashMap<>();
        if (reportes.existsByCancionIdAndUsuarioIdAndMotivoAndEstado(cancionId, uid, motivo, ReporteCancion.Estado.ABIERTO)) {
            r.put("repetido", true);
            return r;
        }
        ReporteCancion rep = new ReporteCancion();
        rep.setCancionId(cancionId);
        rep.setUsuarioId(uid);
        rep.setMotivo(motivo);
        rep.setDetalle(texto(Sanitizador.limpiar(detalle, 500), 500));
        rep.setCliente(cliente != null && cliente.matches("web|android|iphone|tv") ? cliente : "web");
        reportes.save(rep);
        avisarAdmins("REPORTE", uid, "Reporte: “" + c.getNombre() + "” " + MOTIVO_TEXTO.get(motivo), "#/admin?tab=pedidos&ver=reportes");
        r.put("repetido", false);
        r.put("id", rep.getId());
        return r;
    }

    public Page<ReporteCancion> listarReportes(String estado, int pagina, int tam) {
        Collection<ReporteCancion.Estado> estados = estado == null || estado.isBlank()
                ? List.of(ReporteCancion.Estado.values()) : List.of(ReporteCancion.Estado.valueOf(estado));
        return reportes.findByEstadoInOrderByFechaDesc(estados, PageRequest.of(Math.max(0, pagina), Math.min(Math.max(1, tam), 100)));
    }

    @Transactional
    public ReporteCancion responderReporte(Long id, String estado, String respuesta) {
        ReporteCancion r = reportes.findById(id).orElseThrow(() -> new NoSuchElementException("Reporte no encontrado"));
        ReporteCancion.Estado e = ReporteCancion.Estado.valueOf(estado);
        r.setEstado(e);
        r.setRespuesta(texto(respuesta, 300));
        r.setResuelto(e == ReporteCancion.Estado.ABIERTO ? null : LocalDateTime.now());
        if (e == ReporteCancion.Estado.RESUELTO) {
            String nombre = biblioteca.porId(r.getCancionId()).map(CancionDTO::getNombre).orElse("la canción");
            notificaciones.crear(r.getUsuarioId(), "REPORTE", null, "Gracias por avisar: arreglamos “" + nombre + "”"
                    + (r.getRespuesta() != null ? " · " + r.getRespuesta() : ""), null);
        }
        return reportes.save(r);
    }

    public Map<String, Object> conteos() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reportesAbiertos", reportes.countByEstado(ReporteCancion.Estado.ABIERTO));
        return m;
    }

    public Map<String, Object> filaReporte(ReporteCancion r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("motivo", r.getMotivo());
        m.put("motivoTexto", MOTIVO_TEXTO.get(r.getMotivo()));
        m.put("detalle", r.getDetalle());
        m.put("cliente", r.getCliente());
        m.put("estado", r.getEstado());
        m.put("respuesta", r.getRespuesta());
        m.put("fecha", r.getFecha());
        m.put("usuario", usuarios.findById(r.getUsuarioId()).map(Usuario::getUsername).orElse(null));
        m.put("cancion", biblioteca.porId(r.getCancionId()).orElse(null));
        return m;
    }

    private void avisarAdmins(String tipo, Long de, String texto, String ruta) {
        for (Usuario u : usuarios.findAll())
            if (u.getRol() == Usuario.Rol.ADMIN && u.isActivo()) notificaciones.crear(u.getId(), tipo, de, texto, ruta);
    }

    private static String texto(String s, int max) {
        if (s == null) return null;
        s = s.replaceAll("[\\p{Cntrl}&&[^\n]]", "").trim();
        if (s.isEmpty()) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    // ═══════════════════ Tu mes / tu año en SpaceMusic ═══════════════════

    /**
     * Resumen de lo escuchado en un mes ({@code mes} 1-12) o en todo un año ({@code mes} null),
     * calculado con las reproducciones reales de la persona.
     */
    public Map<String, Object> resumen(Long uid, int anio, Integer mes) {
        LocalDate hoy = LocalDate.now();
        if (anio < 2020 || anio > hoy.getYear()) throw new IllegalArgumentException("Año no válido");
        if (mes != null && (mes < 1 || mes > 12)) throw new IllegalArgumentException("Mes no válido");
        LocalDate desde = mes == null ? LocalDate.of(anio, 1, 1) : LocalDate.of(anio, mes, 1);
        LocalDate hasta = mes == null ? desde.plusYears(1) : desde.plusMonths(1);
        if (desde.isAfter(hoy)) throw new IllegalArgumentException("Ese período todavía no empieza");

        List<Object[]> filas = jdbc.query("SELECT cancion_id, fecha, segundos FROM usuario_reproduccion WHERE usuario_id = ? AND fecha >= ? AND fecha < ? ORDER BY fecha",
                (rs, i) -> new Object[]{rs.getLong(1), rs.getTimestamp(2).toLocalDateTime(), (Integer) rs.getObject(3)},
                uid, Timestamp.valueOf(desde.atStartOfDay()), Timestamp.valueOf(hasta.atStartOfDay()));

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("anio", anio);
        r.put("mes", mes);
        r.put("titulo", mes == null ? "Tu " + anio + " en SpaceMusic"
                : "Tu " + Month.of(mes).getDisplayName(TextStyle.FULL, ES) + " en SpaceMusic");
        r.put("desde", desde);
        r.put("hasta", hasta.minusDays(1));
        r.put("enCurso", !hasta.isBefore(hoy.plusDays(1)) && !desde.isAfter(hoy));
        r.put("reproducciones", filas.size());
        if (filas.size() < 5) {
            r.put("suficiente", false);
            r.put("nota", "Todavía no hay suficientes canciones escuchadas en este período para armar tu resumen.");
            return r;
        }
        r.put("suficiente", true);

        Map<Long, long[]> porCancion = new HashMap<>(); // id → [veces, segundos]
        Map<String, long[]> porArtista = new HashMap<>();
        Map<String, Long> porGenero = new HashMap<>();
        Map<String, String> nombreArtista = new HashMap<>();
        Map<String, String> portadaArtista = new HashMap<>();
        long[] porHora = new long[24];
        long[] porDiaSemana = new long[7];
        Map<String, Long> serie = new TreeMap<>();
        Set<LocalDate> dias = new TreeSet<>();
        long segundos = 0;
        CancionDTO primera = null;
        for (Object[] f : filas) {
            long id = (Long) f[0];
            LocalDateTime t = (LocalDateTime) f[1];
            Optional<CancionDTO> oc = biblioteca.porId(id);
            int dur = oc.map(CancionDTO::getDuracionSegundos).orElse(0);
            long s = f[2] != null ? Math.min((Integer) f[2], Math.max(dur, 1) + 5L) : dur;
            segundos += s;
            long[] pc = porCancion.computeIfAbsent(id, k -> new long[2]);
            pc[0]++;
            pc[1] += s;
            porHora[t.getHour()]++;
            porDiaSemana[t.getDayOfWeek().getValue() - 1]++;
            dias.add(t.toLocalDate());
            serie.merge(mes == null ? t.toLocalDate().toString().substring(0, 7) : t.toLocalDate().toString(), s / 60, Long::sum);
            if (oc.isEmpty()) continue;
            CancionDTO c = oc.get();
            if (primera == null) primera = c;
            String clave = BibliotecaService.normalizar(c.getArtista());
            nombreArtista.putIfAbsent(clave, c.getArtista());
            if (c.getPortadaUrl() != null) portadaArtista.putIfAbsent(clave, c.getPortadaUrl());
            long[] pa = porArtista.computeIfAbsent(clave, k -> new long[2]);
            pa[0]++;
            pa[1] += s;
            if (c.getGenero() != null && !c.getGenero().isBlank()) porGenero.merge(c.getGenero(), 1L, Long::sum);
        }
        r.put("minutos", segundos / 60);
        r.put("cancionesDistintas", porCancion.size());
        r.put("artistasDistintos", porArtista.size());
        r.put("diasActivos", dias.size());
        r.put("racha", racha(dias));

        r.put("topCanciones", porCancion.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0])).limit(5)
                .map(e -> biblioteca.porId(e.getKey()).map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("cancion", c);
                    m.put("veces", e.getValue()[0]);
                    return m;
                }).orElse(null)).filter(Objects::nonNull).toList());
        r.put("topArtistas", porArtista.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1])).limit(5)
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("nombre", nombreArtista.get(e.getKey()));
                    m.put("veces", e.getValue()[0]);
                    m.put("minutos", e.getValue()[1] / 60);
                    m.put("portadaUrl", portadaArtista.get(e.getKey()));
                    return m;
                }).toList());
        long totalGen = porGenero.values().stream().mapToLong(Long::longValue).sum();
        r.put("topGeneros", porGenero.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(5)
                .map(e -> Map.of("nombre", e.getKey(), "porcentaje", Math.round(100.0 * e.getValue() / Math.max(1, totalGen)))).toList());

        int hora = 0;
        for (int h = 1; h < 24; h++) if (porHora[h] > porHora[hora]) hora = h;
        r.put("horaFavorita", hora);
        r.put("momento", hora < 6 ? "de madrugada" : hora < 12 ? "por la mañana" : hora < 19 ? "por la tarde" : "por la noche");
        int dia = 0;
        for (int d = 1; d < 7; d++) if (porDiaSemana[d] > porDiaSemana[dia]) dia = d;
        r.put("diaFavorito", DayOfWeek.of(dia + 1).getDisplayName(TextStyle.FULL, ES));
        r.put("primeraCancion", primera);
        r.put("serie", serie.entrySet().stream().map(e -> Map.of("periodo", e.getKey(), "minutos", e.getValue())).toList());
        if (mes == null && !serie.isEmpty()) {
            String mejor = Collections.max(serie.entrySet(), Map.Entry.comparingByValue()).getKey();
            r.put("mesMasActivo", Month.of(Integer.parseInt(mejor.substring(5, 7))).getDisplayName(TextStyle.FULL, ES));
        }
        // Estilo según cuánto explora
        double variedad = (double) porArtista.size() / Math.max(1, filas.size());
        r.put("estilo", variedad > 0.5 ? "Explorador" : variedad > 0.25 ? "Equilibrado" : "Fiel a sus favoritos");

        // Comparación con el período anterior del mismo largo
        LocalDate antDesde = mes == null ? desde.minusYears(1) : desde.minusMonths(1);
        Long antes = jdbc.queryForObject("SELECT COUNT(*) FROM usuario_reproduccion WHERE usuario_id = ? AND fecha >= ? AND fecha < ?",
                Long.class, uid, Timestamp.valueOf(antDesde.atStartOfDay()), Timestamp.valueOf(desde.atStartOfDay()));
        r.put("reproduccionesAnterior", antes == null ? 0 : antes);
        if (antes != null && antes >= 5) r.put("cambioPorcentaje", Math.round(100.0 * (filas.size() - antes) / antes));

        // Descubrimiento del período: el artista que más escuchó y que nunca había escuchado antes
        Set<String> conocidos = new HashSet<>();
        jdbc.query("SELECT DISTINCT cancion_id FROM usuario_reproduccion WHERE usuario_id = ? AND fecha < ?",
                (rs, i) -> rs.getLong(1), uid, Timestamp.valueOf(desde.atStartOfDay()))
                .forEach(id -> biblioteca.porId(id).ifPresent(c -> conocidos.add(BibliotecaService.normalizar(c.getArtista()))));
        porArtista.entrySet().stream()
                .filter(e -> !conocidos.contains(e.getKey()) && e.getValue()[0] >= 3)
                .max(Comparator.comparingLong(e -> e.getValue()[1]))
                .ifPresent(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("nombre", nombreArtista.get(e.getKey()));
                    m.put("veces", e.getValue()[0]);
                    m.put("minutos", e.getValue()[1] / 60);
                    m.put("portadaUrl", portadaArtista.get(e.getKey()));
                    r.put("descubrimiento", m);
                });
        return r;
    }

    /** El día 1 de cada mes (10:00, hora de El Salvador): aviso «Ya está tu resumen de …» a quien escuchó lo suficiente. */
    @org.springframework.scheduling.annotation.Scheduled(cron = "0 0 10 1 * *", zone = "America/El_Salvador")
    public void avisarResumenDelMes() {
        LocalDate desde = LocalDate.now(ZoneId.of("America/El_Salvador")).withDayOfMonth(1).minusMonths(1);
        LocalDate hasta = desde.plusMonths(1);
        String mes = desde.getMonth().getDisplayName(TextStyle.FULL, ES);
        List<Long> quienes = jdbc.queryForList("SELECT usuario_id FROM usuario_reproduccion WHERE fecha >= ? AND fecha < ? GROUP BY usuario_id HAVING COUNT(*) >= 5",
                Long.class, Timestamp.valueOf(desde.atStartOfDay()), Timestamp.valueOf(hasta.atStartOfDay()));
        for (Long uid : quienes) {
            try {
                notificaciones.crear(uid, "RESUMEN", null, "Ya está tu resumen de " + mes + ": tus artistas, canciones y tu descubrimiento del mes",
                        "#/resumen?anio=" + desde.getYear() + "&mes=" + desde.getMonthValue());
            } catch (RuntimeException ignored) { /* un usuario con problema no frena a los demás */ }
        }
    }

    private static int racha(Set<LocalDate> dias) {
        int mejor = 0, actual = 0;
        LocalDate prev = null;
        for (LocalDate d : dias) {
            actual = prev != null && prev.plusDays(1).equals(d) ? actual + 1 : 1;
            mejor = Math.max(mejor, actual);
            prev = d;
        }
        return mejor;
    }

    /** Períodos con datos para elegir (años y meses en que escuchó algo). */
    public Map<String, Object> periodos(Long uid) {
        List<LocalDateTime> extremos = jdbc.query("SELECT MIN(fecha), MAX(fecha) FROM usuario_reproduccion WHERE usuario_id = ?",
                (rs, i) -> rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toLocalDateTime(), uid);
        Map<String, Object> r = new LinkedHashMap<>();
        LocalDateTime inicio = extremos.isEmpty() ? null : extremos.get(0);
        r.put("desde", inicio);
        List<Integer> anios = new ArrayList<>();
        if (inicio != null) for (int a = LocalDate.now().getYear(); a >= inicio.getYear(); a--) anios.add(a);
        r.put("anios", anios);
        return r;
    }
}
