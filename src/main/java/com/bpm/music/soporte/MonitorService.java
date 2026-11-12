package com.bpm.music.soporte;

import com.bpm.music.services.EscanerService;
import com.bpm.music.services.EventosService;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.SpringVersion;
import org.springframework.boot.SpringBootVersion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Métricas reales del servidor: contadores de peticiones (desde MonitorFiltro), memoria, CPU, disco,
 * base de datos y tareas. Cada minuto guarda una muestra (para las gráficas) y los errores por ruta del día.
 * Si algo no se puede medir en esta plataforma se informa como "no disponible" (null), nunca se inventa.
 */
@Service
public class MonitorService {

    private static final Logger log = LoggerFactory.getLogger(MonitorService.class);

    private final MetricaRepository metricas;
    private final EndpointErrorDiaRepository endpointDia;
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final EscanerService escaner;
    private final EventosService eventos;
    private final SesionesService sesiones;

    private final long inicio = System.currentTimeMillis();
    // Totales desde que arrancó el servidor
    private final AtomicLong totalPeticiones = new AtomicLong(), total4xx = new AtomicLong(), total5xx = new AtomicLong();
    // Del minuto en curso
    private final AtomicLong minPeticiones = new AtomicLong(), min4xx = new AtomicLong(), min5xx = new AtomicLong(), minAudioFallos = new AtomicLong();
    private final double[] latencias = new double[2000];
    private int nLat = 0;
    private final Map<String, AtomicLong> erroresPendientes = new ConcurrentHashMap<>();

    private volatile MetricaMuestra ultima;
    private volatile Map<String, Object> actualCache;
    private volatile long actualT;

    public MonitorService(MetricaRepository metricas, EndpointErrorDiaRepository endpointDia, JdbcTemplate jdbc,
                          DataSource dataSource, EscanerService escaner, EventosService eventos, SesionesService sesiones) {
        this.metricas = metricas;
        this.endpointDia = endpointDia;
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.escaner = escaner;
        this.eventos = eventos;
        this.sesiones = sesiones;
    }

    // ═══════════════════ Registro de peticiones ═══════════════════

    // ── Rendimiento por ruta (últimas 400 peticiones de cada una): aplicación, base de datos y consultas ──
    private final Map<String, java.util.concurrent.ConcurrentLinkedDeque<double[]>> tiempos = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger audiosActivos = new java.util.concurrent.atomic.AtomicInteger();

    void registrarTiempos(String endpoint, double appMs, double dbMs, int consultas) {
        var cola = tiempos.computeIfAbsent(endpoint, k -> new java.util.concurrent.ConcurrentLinkedDeque<>());
        cola.addLast(new double[]{appMs, dbMs, consultas});
        while (cola.size() > 400) cola.pollFirst();
    }

    void audioEmpieza() { audiosActivos.incrementAndGet(); }
    void audioTermina() { audiosActivos.decrementAndGet(); }

    /** Cuántas canciones se están enviando ahora mismo (las tareas pesadas esperan si hay reproducción). */
    public int audiosActivos() { return Math.max(0, audiosActivos.get()); }

    /** p50/p95 de cada ruta (aplicación y base de datos) con las últimas peticiones. */
    public List<Map<String, Object>> rendimiento() {
        List<Map<String, Object>> r = new ArrayList<>();
        tiempos.forEach((ruta, cola) -> {
            List<double[]> l = new ArrayList<>(cola);
            if (l.size() < 3) return;
            double[] app = l.stream().mapToDouble(x -> x[0]).sorted().toArray();
            double[] db = l.stream().mapToDouble(x -> x[1]).sorted().toArray();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ruta", ruta);
            m.put("peticiones", l.size());
            m.put("appP50", redondear(app[app.length / 2]));
            m.put("appP95", redondear(app[(int) Math.min(app.length - 1, Math.ceil(app.length * 0.95) - 1)]));
            m.put("dbP50", redondear(db[db.length / 2]));
            m.put("dbP95", redondear(db[(int) Math.min(db.length - 1, Math.ceil(db.length * 0.95) - 1)]));
            m.put("consultasMedia", redondear(l.stream().mapToDouble(x -> x[2]).average().orElse(0)));
            r.add(m);
        });
        r.sort(Comparator.comparingDouble((Map<String, Object> m) -> ((Number) m.get("appP95")).doubleValue()).reversed());
        return r;
    }

    void registrarPeticion(String endpoint, int estado, double ms, boolean audio) {
        totalPeticiones.incrementAndGet();
        minPeticiones.incrementAndGet();
        if (estado >= 500) { total5xx.incrementAndGet(); min5xx.incrementAndGet(); }
        else if (estado >= 400) { total4xx.incrementAndGet(); min4xx.incrementAndGet(); }
        if (audio && estado >= 400 && estado != 401 && estado != 416) minAudioFallos.incrementAndGet();
        if (estado >= 400 && estado != 401 && estado != 404) erroresPendientes.computeIfAbsent(endpoint + "|" + estado, k -> new AtomicLong()).incrementAndGet();
        if (ms >= 0) synchronized (latencias) { latencias[nLat % latencias.length] = ms; nLat++; }
    }

    // ═══════════════════ Muestra por minuto ═══════════════════

    @Scheduled(initialDelay = 30_000, fixedRate = 60_000)
    public void muestrear() {
        try {
            MetricaMuestra m = medir(true);
            metricas.save(m);
            ultima = m;
            guardarErroresPorRuta();
        } catch (Exception e) {
            log.debug("No se pudo guardar la muestra del monitor: {}", e.getMessage());
        }
    }

    private MetricaMuestra medir(boolean cerrarMinuto) {
        MetricaMuestra m = new MetricaMuestra();
        m.setFecha(LocalDateTime.now());
        Runtime rt = Runtime.getRuntime();
        m.setMemoriaUsadaMb((rt.totalMemory() - rt.freeMemory()) / 1_048_576);
        m.setMemoriaMaxMb(rt.maxMemory() / 1_048_576);
        m.setCpuProceso(cpu(true));
        m.setCpuSistema(cpu(false));
        File disco = raizMusica();
        if (disco != null) {
            m.setDiscoLibreGb(redondear(disco.getUsableSpace() / 1e9));
            m.setDiscoTotalGb(redondear(disco.getTotalSpace() / 1e9));
        }
        m.setAlmacenamientoOk(disco != null && disco.isDirectory() && disco.canRead());
        if (cerrarMinuto) {
            m.setSolicitudes(minPeticiones.getAndSet(0));
            m.setErrores4xx(min4xx.getAndSet(0));
            m.setErrores5xx(min5xx.getAndSet(0));
            m.setFallosAudio(minAudioFallos.getAndSet(0));
            m.setFallosLogin(sesiones.fallosLoginDesde(System.currentTimeMillis() - 60_000));
            double[] l;
            synchronized (latencias) {
                int n = Math.min(nLat, latencias.length);
                l = Arrays.copyOf(latencias, n);
                nLat = 0;
            }
            if (l.length > 0) {
                Arrays.sort(l);
                m.setLatenciaMediaMs(redondear(Arrays.stream(l).average().orElse(0)));
                m.setLatenciaP95Ms(redondear(l[(int) Math.min(l.length - 1, Math.floor(l.length * 0.95))]));
            }
        }
        double[] db = pingDb();
        m.setDbOk(db != null);
        m.setDbLatenciaMs(db == null ? null : redondear(db[0]));
        HikariPoolMXBean pool = pool();
        m.setDbConexionesActivas(pool == null ? null : pool.getActiveConnections());
        m.setConexionesEnVivo(eventos.conectados().values().stream().mapToInt(Integer::intValue).sum());
        m.setHilos(ManagementFactory.getThreadMXBean().getThreadCount());
        return m;
    }

    private void guardarErroresPorRuta() {
        if (erroresPendientes.isEmpty()) return;
        LocalDate hoy = LocalDate.now();
        Map<String, AtomicLong> copia = new HashMap<>(erroresPendientes);
        erroresPendientes.clear();
        copia.forEach((k, n) -> {
            int i = k.lastIndexOf('|');
            String ep = k.substring(0, i);
            int estado = Integer.parseInt(k.substring(i + 1));
            EndpointErrorDia e = endpointDia.findByDiaAndEndpointAndEstadoHttp(hoy, ep, estado).orElseGet(() -> {
                EndpointErrorDia x = new EndpointErrorDia();
                x.setDia(hoy); x.setEndpoint(ep.length() > 140 ? ep.substring(0, 140) : ep); x.setEstadoHttp(estado);
                return x;
            });
            e.setCuenta(e.getCuenta() + n.get());
            endpointDia.save(e);
        });
    }

    // ═══════════════════ Estado actual ═══════════════════

    /** Estado general (se recalcula como mucho cada 5 s para no cargar el servidor). */
    public Map<String, Object> actual() {
        if (actualCache != null && System.currentTimeMillis() - actualT < 5_000) return actualCache;
        MetricaMuestra m = medir(false);
        MetricaMuestra minuto = ultima;
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("actualizado", LocalDateTime.now().toString());
        r.put("activoSegundos", (System.currentTimeMillis() - inicio) / 1000);
        r.put("memoriaUsadaMb", m.getMemoriaUsadaMb());
        r.put("memoriaMaxMb", m.getMemoriaMaxMb());
        r.put("cpuProceso", m.getCpuProceso());
        r.put("cpuSistema", m.getCpuSistema());
        r.put("procesadores", Runtime.getRuntime().availableProcessors());
        r.put("discoLibreGb", m.getDiscoLibreGb());
        r.put("discoTotalGb", m.getDiscoTotalGb());
        r.put("hilos", m.getHilos());
        r.put("conexionesEnVivo", m.getConexionesEnVivo());
        r.put("totalPeticiones", totalPeticiones.get());
        r.put("total4xx", total4xx.get());
        r.put("total5xx", total5xx.get());
        r.put("ultimoMinuto", minuto == null ? null : Map.of(
                "fecha", minuto.getFecha().toString(),
                "solicitudes", minuto.getSolicitudes(), "errores4xx", minuto.getErrores4xx(), "errores5xx", minuto.getErrores5xx(),
                "latenciaMediaMs", minuto.getLatenciaMediaMs() == null ? "No disponible" : minuto.getLatenciaMediaMs(),
                "latenciaP95Ms", minuto.getLatenciaP95Ms() == null ? "No disponible" : minuto.getLatenciaP95Ms(),
                "fallosAudio", minuto.getFallosAudio()));
        // Estado de cada componente: operativo, degradado, no_disponible o no_comprobado
        Map<String, Object> comp = new LinkedHashMap<>();
        comp.put("aplicacion", componente("operativo", "El servidor responde · versión " + System.getProperty("java.version"), m.getFecha()));
        double tasa5xx = minuto == null || minuto.getSolicitudes() == null || minuto.getSolicitudes() == 0 ? 0
                : (double) minuto.getErrores5xx() / minuto.getSolicitudes();
        comp.put("api", minuto == null ? componente("no_comprobado", "Aún no hay un minuto de datos", null)
                : componente(tasa5xx > 0.2 ? "degradado" : "operativo",
                String.format(Locale.ROOT, "%d peticiones y %d errores del servidor en el último minuto", minuto.getSolicitudes(), minuto.getErrores5xx()), minuto.getFecha()));
        comp.put("baseDatos", Boolean.TRUE.equals(m.getDbOk())
                ? componente(m.getDbLatenciaMs() > 500 ? "degradado" : "operativo", "Responde en " + m.getDbLatenciaMs() + " ms", m.getFecha())
                : componente("no_disponible", "No responde", m.getFecha()));
        comp.put("almacenamiento", Boolean.TRUE.equals(m.getAlmacenamientoOk())
                ? componente(m.getDiscoTotalGb() != null && m.getDiscoLibreGb() != null && m.getDiscoLibreGb() / m.getDiscoTotalGb() < 0.05 ? "degradado" : "operativo",
                m.getDiscoLibreGb() + " GB libres", m.getFecha())
                : componente("no_disponible", "La carpeta de música no se puede leer", m.getFecha()));
        Map<String, Object> esc = escaner.estado();
        // Tareas en segundo plano: el monitor deja una muestra por minuto; si la última es vieja, algo se detuvo
        boolean monitorVivo = minuto != null && minuto.getFecha().isAfter(LocalDateTime.now().minusMinutes(3));
        Object errEsc = esc.get("ultimoError");
        comp.put("tareas", minuto == null ? componente("no_comprobado", "Escáner: " + esc.get("fase") + " · el monitor aún no guarda su primera muestra", null)
                : componente(!monitorVivo ? "degradado" : errEsc != null && !String.valueOf(errEsc).isBlank() ? "degradado" : "operativo",
                "Escáner: " + esc.get("fase") + (monitorVivo ? " · tareas programadas activas" : " · las tareas programadas no responden"), minuto.getFecha()));
        r.put("componentes", comp);
        String general = comp.values().stream().map(x -> (String) ((Map<?, ?>) x).get("estado"))
                .anyMatch("no_disponible"::equals) ? "no_disponible"
                : comp.values().stream().map(x -> (String) ((Map<?, ?>) x).get("estado")).anyMatch("degradado"::equals) ? "degradado" : "operativo";
        r.put("estado", general);
        HikariPoolMXBean pool = pool();
        r.put("pool", pool == null ? null : Map.of("activas", pool.getActiveConnections(), "libres", pool.getIdleConnections(),
                "total", pool.getTotalConnections(), "esperando", pool.getThreadsAwaitingConnection()));
        r.put("version", Map.of("java", System.getProperty("java.version"), "springBoot", String.valueOf(SpringBootVersion.getVersion()),
                "spring", String.valueOf(SpringVersion.getVersion()), "sistema", System.getProperty("os.name") + " " + System.getProperty("os.arch")));
        r.put("escaner", esc);
        actualCache = r;
        actualT = System.currentTimeMillis();
        return r;
    }

    /** Muestras de las últimas horas (reducidas a ~240 puntos para no enviar de más). */
    public List<MetricaMuestra> historial(int horas) {
        List<MetricaMuestra> l = metricas.findByFechaAfterOrderByFechaAsc(LocalDateTime.now().minusHours(Math.min(Math.max(horas, 1), 24 * 31)));
        if (l.size() <= 240) return l;
        int paso = (int) Math.ceil(l.size() / 240.0);
        List<MetricaMuestra> r = new ArrayList<>();
        for (int i = 0; i < l.size(); i += paso) r.add(l.get(i));
        return r;
    }

    public MetricaMuestra ultimaMuestra() { return ultima; }

    // ═══════════════════ Mediciones ═══════════════════

    /** [latencia ms] o null si la base no responde. Consulta mínima (SELECT 1). */
    public double[] pingDb() {
        try {
            long t = System.nanoTime();
            jdbc.queryForObject("SELECT 1", Integer.class);
            return new double[]{(System.nanoTime() - t) / 1e6};
        } catch (Exception e) {
            return null;
        }
    }

    public HikariPoolMXBean pool() {
        try {
            return dataSource instanceof HikariDataSource h ? h.getHikariPoolMXBean()
                    : dataSource.isWrapperFor(HikariDataSource.class) ? dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Double cpu(boolean proceso) {
        try {
            var os = ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean s) {
                double v = proceso ? s.getProcessCpuLoad() : s.getCpuLoad();
                return v < 0 ? null : redondear(v * 100);
            }
        } catch (Throwable ignored) { }
        return null;
    }

    File raizMusica() {
        try { return escaner.raiz(); } catch (Exception e) { return null; }
    }

    private static Map<String, Object> componente(String estado, String detalle, LocalDateTime fecha) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("estado", estado);
        c.put("detalle", detalle);
        c.put("comprobado", fecha == null ? null : fecha.toString());
        return c;
    }

    static double redondear(double v) {
        return Math.round(v * 10) / 10.0;
    }

    public long activoSegundos() { return (System.currentTimeMillis() - inicio) / 1000; }
}
