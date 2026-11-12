package com.bpm.music.organizador;

import com.bpm.music.soporte.ConfigSoporteService;
import com.bpm.music.soporte.ConfigSoporteRepository;
import com.bpm.music.soporte.ConfigSoporte;
import com.bpm.music.soporte.MonitorService;
import com.bpm.music.services.BibliotecaService;
import com.bpm.music.dtos.CancionDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Organizador automático del catálogo. Cada día (canciones nuevas o cambiadas) y cada semana (revisión general)
 * a la hora configurada de El Salvador, o a mano desde Administración. Etapas:
 *   vincular con YouTube Music → relacionadas → letras → portadas → audio → duplicados/inconsistencias → IA (opcional).
 * Una sola ejecución a la vez; el avance se guarda tras cada lote y se retoma si el servidor se reinicia.
 * Espera mientras alguien escucha música o la CPU está alta; nunca va en la ruta de reproducción.
 * Si la IA está apagada o no supera la evaluación, todo lo demás funciona igual.
 */
@Service
public class OrganizadorService {

    private static final Logger log = LoggerFactory.getLogger(OrganizadorService.class);
    public static final ZoneId ZONA = ZoneId.of("America/El_Salvador");
    private static final List<TrabajoOrganizador.Estado> VIVOS = List.of(TrabajoOrganizador.Estado.EN_CURSO, TrabajoOrganizador.Estado.PAUSADO);

    record Etapa(String clave, String nombre) {}
    static final List<Etapa> ETAPAS = List.of(
            new Etapa("vincular", "Vincular canciones con YouTube Music"),
            new Etapa("relacionadas", "Canciones relacionadas (recomendaciones)"),
            new Etapa("letras", "Completar letras (LRCLIB)"),
            new Etapa("portadas", "Completar portadas y año (MusicBrainz + Cover Art Archive)"),
            new Etapa("audio", "Analizar el sonido (ffmpeg)"),
            new Etapa("revision", "Buscar duplicados e inconsistencias"),
            new Etapa("ia", "Etiquetas con IA local (opcional)"));

    private final TrabajoOrganizadorRepository trabajos;
    private final ConfigSoporteService config;
    private final ConfigSoporteRepository configRepo;
    private final MonitorService monitor;
    private final SenalesExternasService senales;
    private final CompletadoService completado;
    private final AudioAnalisisService audio;
    private final RevisionCatalogoService revision;
    private final IaLocalService ia;
    private final EtiquetaCancionRepository etiquetas;
    private final BibliotecaService biblioteca;
    private final ObjectMapper json = new ObjectMapper();

    private final AtomicBoolean corriendo = new AtomicBoolean();
    private volatile boolean detener;
    private volatile String esperandoPor;

    public OrganizadorService(TrabajoOrganizadorRepository trabajos, ConfigSoporteService config, ConfigSoporteRepository configRepo,
                              MonitorService monitor, SenalesExternasService senales, CompletadoService completado,
                              AudioAnalisisService audio, RevisionCatalogoService revision, IaLocalService ia,
                              EtiquetaCancionRepository etiquetas, BibliotecaService biblioteca) {
        this.trabajos = trabajos;
        this.config = config;
        this.configRepo = configRepo;
        this.monitor = monitor;
        this.senales = senales;
        this.completado = completado;
        this.audio = audio;
        this.revision = revision;
        this.ia = ia;
        this.etiquetas = etiquetas;
        this.biblioteca = biblioteca;
    }

    // ═══════════════════ Programación ═══════════════════

    /** Cada minuto (hora de El Salvador): ¿toca la revisión diaria o la semanal? */
    @Scheduled(cron = "0 * * * * *", zone = "America/El_Salvador")
    public void reloj() {
        if (config.valor("org.activo") < 1 || corriendo.get()) return;
        ZonedDateTime ahora = ZonedDateTime.now(ZONA);
        int h = ahora.getHour(), m = ahora.getMinute();
        boolean semanal = ahora.getDayOfWeek().getValue() == (int) config.valor("org.diaSemanal") && h == (int) config.valor("org.horaSemanal") && m == 0;
        boolean diaria = h == (int) config.valor("org.horaDiaria") && m == (int) config.valor("org.minutoDiario");
        try {
            if (semanal) iniciar("semanal", "programación");
            else if (diaria) iniciar("diario", "programación");
        } catch (IllegalStateException ignored) { /* ya hay una en curso */ }
    }

    public Map<String, Object> proximas() {
        ZonedDateTime ahora = ZonedDateTime.now(ZONA);
        ZonedDateTime d = ahora.withHour((int) config.valor("org.horaDiaria")).withMinute((int) config.valor("org.minutoDiario")).withSecond(0).withNano(0);
        if (!d.isAfter(ahora)) d = d.plusDays(1);
        ZonedDateTime s = ahora.with(java.time.temporal.TemporalAdjusters.nextOrSame(DayOfWeek.of((int) config.valor("org.diaSemanal"))))
                .withHour((int) config.valor("org.horaSemanal")).withMinute(0).withSecond(0).withNano(0);
        if (!s.isAfter(ahora)) s = s.plusWeeks(1);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("zona", ZONA.getId());
        r.put("diaria", config.valor("org.activo") >= 1 ? d.toOffsetDateTime().toString() : null);
        r.put("semanal", config.valor("org.activo") >= 1 ? s.toOffsetDateTime().toString() : null);
        return r;
    }

    /** Si el servidor se reinició a mitad de un trabajo, se retoma donde iba. */
    @EventListener(ApplicationReadyEvent.class)
    public void retomar() {
        trabajos.findFirstByEstadoInOrderByIdDesc(List.of(TrabajoOrganizador.Estado.EN_CURSO)).ifPresent(t -> {
            log.info("Organizador: se retoma el trabajo #{} en la etapa {}", t.getId(), t.getEtapa());
            try { arrancar(t); } catch (IllegalStateException e) { log.info("Organizador: ya hay uno en marcha"); }
        });
    }

    // ═══════════════════ Control ═══════════════════

    public synchronized TrabajoOrganizador iniciar(String tipo, String quien) {
        if (corriendo.get() || trabajos.findFirstByEstadoInOrderByIdDesc(VIVOS).isPresent())
            throw new IllegalStateException("Ya hay un trabajo del organizador en curso o en pausa");
        TrabajoOrganizador t = new TrabajoOrganizador();
        t.setTipo(tipo);
        t.setEstado(TrabajoOrganizador.Estado.EN_CURSO);
        t.setIniciadoPor(quien);
        t.setAvance("{}");
        t = trabajos.save(t);
        arrancar(t);
        return t;
    }

    public synchronized TrabajoOrganizador pausar() {
        TrabajoOrganizador t = trabajos.findFirstByEstadoOrderByIdDesc(TrabajoOrganizador.Estado.EN_CURSO)
                .orElseThrow(() -> new IllegalArgumentException("No hay un trabajo en curso"));
        detener = true;
        t.setEstado(TrabajoOrganizador.Estado.PAUSADO);
        t.setMensaje("En pausa");
        t.setActualizado(LocalDateTime.now());
        return trabajos.save(t);
    }

    public synchronized TrabajoOrganizador reanudar() {
        TrabajoOrganizador t = trabajos.findFirstByEstadoOrderByIdDesc(TrabajoOrganizador.Estado.PAUSADO)
                .orElseThrow(() -> new IllegalArgumentException("No hay un trabajo en pausa"));
        if (corriendo.get()) throw new IllegalStateException("Espera a que termine de detenerse");
        t.setEstado(TrabajoOrganizador.Estado.EN_CURSO);
        t.setMensaje("Reanudado");
        trabajos.save(t);
        arrancar(t);
        return t;
    }

    public synchronized TrabajoOrganizador cancelar() {
        TrabajoOrganizador t = trabajos.findFirstByEstadoInOrderByIdDesc(VIVOS)
                .orElseThrow(() -> new IllegalArgumentException("No hay un trabajo para cancelar"));
        detener = true;
        t.setEstado(TrabajoOrganizador.Estado.CANCELADO);
        t.setMensaje("Cancelado: lo hecho hasta ahora se conserva");
        t.setFin(LocalDateTime.now());
        return trabajos.save(t);
    }

    private void arrancar(TrabajoOrganizador t) {
        if (!corriendo.compareAndSet(false, true)) throw new IllegalStateException("Ya hay un trabajo en marcha");
        detener = false;
        Thread.ofVirtual().name("organizador-" + t.getId()).start(() -> {
            try { ejecutar(t.getId()); }
            catch (Exception e) {
                log.warn("Organizador: el trabajo #{} falló: {}", t.getId(), e.getMessage());
                trabajos.findById(t.getId()).ifPresent(x -> {
                    if (x.getEstado() == TrabajoOrganizador.Estado.EN_CURSO) {
                        x.setEstado(TrabajoOrganizador.Estado.FALLIDO);
                        x.setMensaje("Falló: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().substring(0, Math.min(200, e.getMessage().length()))));
                        x.setFin(LocalDateTime.now());
                        trabajos.save(x);
                    }
                });
            } finally {
                corriendo.set(false);
                esperandoPor = null;
            }
        });
    }

    // ═══════════════════ Ejecución ═══════════════════

    void ejecutar(Long id) throws Exception {
        TrabajoOrganizador t = trabajos.findById(id).orElseThrow();
        long limite = System.currentTimeMillis() + (long) config.valor("org.maxMinutos") * 60_000;
        @SuppressWarnings("unchecked")
        Map<String, Object> avance = new LinkedHashMap<>(json.readValue(Objects.toString(t.getAvance(), "{}"), Map.class));
        BooleanSupplier seguir = () -> !detener && System.currentTimeMillis() < limite;
        int lote = (int) config.valor("org.lote");
        for (int i = t.getEtapa(); i < ETAPAS.size(); i++) {
            Etapa e = ETAPAS.get(i);
            t.setEtapa(i);
            guardar(t, avance, e.nombre());
            int total = ((Number) avance.getOrDefault(e.clave(), 0)).intValue();
            while (seguir.getAsBoolean()) {
                if (!esperarRecursos(seguir)) break;
                int n = paso(e.clave(), lote, seguir, avance);
                total += n;
                avance.put(e.clave(), total);
                guardar(t, avance, e.nombre() + " · " + total + " procesadas");
                if (n == 0 || e.clave().equals("revision")) break; // revision es una sola pasada
            }
            if (detener) return; // pausa o cancelación: el estado ya se guardó
            if (System.currentTimeMillis() >= limite) {
                avance.put("nota", "Se alcanzó el tiempo máximo; seguirá en la próxima ejecución");
                break;
            }
        }
        ia.descargar(modelo()); // por si quedó en memoria
        t = trabajos.findById(id).orElseThrow();
        if (t.getEstado() != TrabajoOrganizador.Estado.EN_CURSO) return;
        t.setEstado(TrabajoOrganizador.Estado.TERMINADO);
        t.setFin(LocalDateTime.now());
        t.setAvance(json.writeValueAsString(avance));
        t.setMensaje("Terminado");
        trabajos.save(t);
    }

    public boolean estaCorriendo() { return corriendo.get(); }

    private int paso(String etapa, int lote, BooleanSupplier seguir, Map<String, Object> avance) {
        return switch (etapa) {
            case "vincular" -> senales.vincularLote(lote, seguir);
            case "relacionadas" -> senales.relacionadasLote(Math.max(5, lote / 2), seguir);
            case "letras" -> completado.letrasLote(lote, seguir);
            case "portadas" -> completado.portadasLote(Math.max(5, lote / 2), seguir);
            case "audio" -> audio.lote(lote, seguir);
            case "revision" -> revision.revisar();
            case "ia" -> etiquetarConIa(Math.max(5, lote / 4), seguir, avance);
            default -> 0;
        };
    }

    /**
     * Espera (sin trabajar) mientras alguien escucha música o la CPU del sistema está alta.
     * Devuelve false si mientras tanto se pidió detener o se acabó el tiempo.
     */
    private boolean esperarRecursos(BooleanSupplier seguir) throws InterruptedException {
        while (seguir.getAsBoolean()) {
            if (config.valor("org.pausaSiReproduce") >= 1 && monitor.audiosActivos() > 0) esperandoPor = "Alguien está escuchando música";
            else if (cpu() > config.valor("org.cpuMax")) esperandoPor = "CPU del sistema alta";
            else { esperandoPor = null; return true; }
            Thread.sleep(20_000);
        }
        return false;
    }

    private static double cpu() {
        try {
            var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            double c = os.getCpuLoad();
            return c < 0 ? 0 : c * 100;
        } catch (Exception e) {
            return 0;
        }
    }

    private void guardar(TrabajoOrganizador t, Map<String, Object> avance, String mensaje) throws Exception {
        TrabajoOrganizador actual = trabajos.findById(t.getId()).orElseThrow();
        if (actual.getEstado() != TrabajoOrganizador.Estado.EN_CURSO) { detener = true; return; }
        actual.setEtapa(t.getEtapa());
        actual.setAvance(json.writeValueAsString(avance));
        actual.setMensaje(mensaje.length() > 290 ? mensaje.substring(0, 290) : mensaje);
        actual.setActualizado(LocalDateTime.now());
        trabajos.save(actual);
    }

    // ═══════════════════ IA (opcional y con evaluación previa) ═══════════════════

    public String modelo() {
        return configRepo.findById("org.modelo").map(ConfigSoporte::getValor).orElse("qwen3:0.6b");
    }

    /** Última evaluación guardada: "precision|segundos|fecha|modelo". */
    public Map<String, Object> ultimaEvaluacion() {
        return configRepo.findById("org.evaluacion").map(c -> {
            String[] p = c.getValor().split("\\|", 4);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("precision", Double.valueOf(p[0]));
            m.put("segundosPorCancion", p[1].isEmpty() ? null : Double.valueOf(p[1]));
            m.put("fecha", p[2]);
            m.put("modelo", p.length > 3 ? p[3] : null);
            m.put("apta", Double.parseDouble(p[0]) >= IaLocalService.PRECISION_MINIMA);
            return m;
        }).orElse(null);
    }

    private volatile boolean evaluando;
    private volatile Map<String, Object> ultimaEvaluacionCompleta;

    /** La evaluación tarda minutos (20 canciones): se hace en segundo plano. */
    public synchronized boolean evaluarEnSegundoPlano(String quien) {
        if (evaluando || corriendo.get()) return false;
        evaluando = true;
        Thread.ofVirtual().name("organizador-evaluacion").start(() -> {
            try { ultimaEvaluacionCompleta = evaluar(quien); }
            catch (Exception e) { ultimaEvaluacionCompleta = Map.of("error", Objects.toString(e.getMessage(), "Error")); }
            finally { evaluando = false; }
        });
        return true;
    }

    public boolean evaluando() { return evaluando; }

    public Map<String, Object> evaluar(String quien) {
        if (corriendo.get()) throw new IllegalStateException("Espera a que termine el trabajo en curso");
        Map<String, Object> r = ia.evaluar(modelo(), (int) config.valor("org.iaHilos"));
        ConfigSoporte c = configRepo.findById("org.evaluacion").orElseGet(ConfigSoporte::new);
        c.setClave("org.evaluacion");
        String v = r.get("precision") + "|" + Objects.toString(r.get("segundosPorCancion"), "") + "|" + LocalDate.now() + "|" + modelo();
        c.setValor(v.length() > 120 ? v.substring(0, 120) : v);
        c.setActualizada(LocalDateTime.now());
        c.setActualizadaPor(quien);
        configRepo.save(c);
        return r;
    }

    public void cambiarModelo(String nombre, String quien) {
        if (nombre == null || !nombre.matches("[A-Za-z0-9._:-]{2,60}")) throw new IllegalArgumentException("Nombre de modelo no válido");
        ConfigSoporte c = configRepo.findById("org.modelo").orElseGet(ConfigSoporte::new);
        c.setClave("org.modelo");
        c.setValor(nombre);
        c.setActualizada(LocalDateTime.now());
        c.setActualizadaPor(quien);
        configRepo.save(c);
    }

    /** Propone etiquetas de estilo y contexto con la IA, solo si está activada y su última evaluación la aprueba. */
    int etiquetarConIa(int n, BooleanSupplier seguir, Map<String, Object> avance) {
        Map<String, Object> ev = ultimaEvaluacion();
        if (config.valor("org.iaActiva") < 1) { avance.put("ia", "apagada"); return 0; }
        if (ev == null || !Boolean.TRUE.equals(ev.get("apta")) || !modelo().equals(ev.get("modelo"))) {
            avance.put("ia", "sin evaluación aprobada para " + modelo());
            return 0;
        }
        Set<Long> hechas = new HashSet<>(etiquetas.conEtiquetaDe("ia"));
        int hilos = (int) config.valor("org.iaHilos"), k = 0;
        for (CancionDTO c : biblioteca.todas()) {
            if (k >= n || !seguir.getAsBoolean()) break;
            if (hechas.contains(c.getId())) continue;
            try {
                Map<String, String> r = ia.etiquetar(modelo(), c.getArtista(), c.getNombre(), hilos, true);
                if (r != null) {
                    for (String tipo : List.of("estilo", "contexto")) {
                        EtiquetaCancion e = new EtiquetaCancion();
                        e.setCancionId(c.getId());
                        e.setTipo(tipo);
                        e.setValor(r.get(tipo));
                        e.setOrigen("ia");
                        e.setModelo(modelo());
                        e.setEstado(EtiquetaCancion.Estado.PROPUESTA); // nunca se aplica sola
                        etiquetas.save(e);
                    }
                }
            } catch (Exception e) {
                break; // Ollama apagado o lento: se sigue otro día
            }
            k++;
        }
        ia.descargar(modelo());
        return k;
    }

    // ═══════════════════ Estado para Administración ═══════════════════

    public Map<String, Object> estado() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("corriendo", corriendo.get());
        r.put("esperandoPor", esperandoPor);
        r.put("actual", trabajos.findFirstByEstadoInOrderByIdDesc(VIVOS).orElse(null));
        r.put("ultimos", trabajos.findTop20ByOrderByIdDesc());
        r.put("etapas", ETAPAS.stream().map(e -> Map.of("clave", e.clave(), "nombre", e.nombre())).toList());
        r.put("proximas", proximas());
        r.put("ajustes", config.organizador());
        r.put("modelo", modelo());
        r.put("evaluacion", ultimaEvaluacion());
        r.put("evaluando", evaluando);
        r.put("evaluacionDetalle", ultimaEvaluacionCompleta);
        return r;
    }
}
