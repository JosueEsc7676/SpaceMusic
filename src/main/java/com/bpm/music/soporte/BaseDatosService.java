package com.bpm.music.soporte;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.bpm.music.services.RegistroErrores;
import com.zaxxer.hikari.HikariPoolMXBean;
import jakarta.annotation.PostConstruct;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Monitorización de la base de datos (solo lectura): conexión, latencia, pool de conexiones, consultas lentas
 * que informa Hibernate, espacio usado y comprobaciones de integridad. No hay editor SQL ni cambios de datos.
 */
@Service
public class BaseDatosService {

    private final JdbcTemplate jdbc;
    private final MonitorService monitor;
    private final IncidenciaRepository incidencias;
    private final MetricaRepository metricas;
    private final Environment env;
    private final ConcurrentLinkedDeque<Map<String, Object>> lentas = new ConcurrentLinkedDeque<>();
    private volatile Map<String, Object> integridadCache;

    public BaseDatosService(JdbcTemplate jdbc, MonitorService monitor, IncidenciaRepository incidencias,
                            MetricaRepository metricas, Environment env) {
        this.jdbc = jdbc;
        this.monitor = monitor;
        this.incidencias = incidencias;
        this.metricas = metricas;
        this.env = env;
    }

    @PostConstruct
    void conectar() {
        RegistroErrores.consultasLentas = this::lenta;
    }

    private void lenta(ILoggingEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fecha", LocalDateTime.ofInstant(Instant.ofEpochMilli(e.getTimeStamp()), ZoneId.systemDefault()).toString());
        // Hibernate escribe la consulta con "?" en lugar de los valores: igual se limpia por si acaso
        m.put("consulta", Sanitizador.limpiar(e.getFormattedMessage(), 800));
        lentas.addFirst(m);
        while (lentas.size() > 100) lentas.pollLast();
    }

    public Map<String, Object> estado() {
        Map<String, Object> r = new LinkedHashMap<>();
        double[] ping = monitor.pingDb();
        r.put("conectada", ping != null);
        r.put("latenciaMs", ping == null ? null : MonitorService.redondear(ping[0]));
        r.put("comprobado", LocalDateTime.now().toString());
        String url = env.getProperty("spring.datasource.url", "");
        r.put("motor", url.contains(":h2:") ? "H2 (desarrollo)" : url.contains(":mysql:") ? "MySQL" : "Desconocido");
        HikariPoolMXBean p = monitor.pool();
        r.put("pool", p == null ? "No disponible" : Map.of("activas", p.getActiveConnections(), "libres", p.getIdleConnections(),
                "total", p.getTotalConnections(), "esperando", p.getThreadsAwaitingConnection(),
                "maximo", env.getProperty("spring.datasource.hikari.maximum-pool-size", "10")));
        // Latencia de las últimas 24 h (de las muestras por minuto)
        List<MetricaMuestra> h = metricas.findByFechaAfterOrderByFechaAsc(LocalDateTime.now().minusHours(24));
        DoubleSummaryStatistics st = h.stream().filter(m -> m.getDbLatenciaMs() != null).mapToDouble(MetricaMuestra::getDbLatenciaMs).summaryStatistics();
        r.put("latencia24h", st.getCount() == 0 ? "No disponible" : Map.of("media", MonitorService.redondear(st.getAverage()),
                "maxima", MonitorService.redondear(st.getMax()), "muestras", st.getCount()));
        long caidas = h.stream().filter(m -> Boolean.FALSE.equals(m.getDbOk())).count();
        r.put("minutosSinConexion24h", caidas);
        r.put("erroresDeDatos", incidencias.buscar(null, null, "base de datos", null, null, LocalDateTime.now().minusDays(7), null,
                org.springframework.data.domain.PageRequest.of(0, 1)).getTotalElements());
        r.put("consultasLentas", new ArrayList<>(lentas));
        r.put("umbralLentaMs", env.getProperty("spring.jpa.properties.hibernate.log_slow_query", "No configurado"));
        r.put("almacenamiento", almacenamiento(url));
        r.put("esquema", Map.of("sistema", "Hibernate ddl-auto=" + env.getProperty("spring.jpa.hibernate.ddl-auto", "?"),
                "migraciones", "El proyecto no usa un sistema de migraciones (Flyway/Liquibase): Hibernate crea tablas y columnas nuevas al arrancar"));
        r.put("integridad", integridadCache);
        return r;
    }

    private Object almacenamiento(String url) {
        if (!url.contains(":mysql:")) return "No disponible en este motor";
        try {
            List<Map<String, Object>> filas = jdbc.queryForList(
                    "SELECT table_name AS tabla, table_rows AS filas, ROUND((data_length + index_length) / 1048576, 2) AS mb "
                            + "FROM information_schema.tables WHERE table_schema = DATABASE() ORDER BY (data_length + index_length) DESC LIMIT 25");
            double total = filas.stream().mapToDouble(f -> f.get("mb") == null ? 0 : ((Number) f.get("mb")).doubleValue()).sum();
            return Map.of("totalMb", MonitorService.redondear(total), "tablas", filas);
        } catch (Exception e) {
            return "No disponible: " + Sanitizador.limpiar(e.getMessage(), 150);
        }
    }

    /** Comprobaciones de integridad de solo lectura (referencias a canciones o cuentas que ya no existen). */
    public Map<String, Object> comprobarIntegridad() {
        String[][] pruebas = {
                {"Canciones en playlists que ya no existen", "SELECT COUNT(*) FROM playlist_cancion pc LEFT JOIN cancion c ON c.id = pc.cancion_id WHERE c.id IS NULL"},
                {"Me gusta de canciones que ya no existen", "SELECT COUNT(*) FROM usuario_favorito f LEFT JOIN cancion c ON c.id = f.cancion_id WHERE c.id IS NULL"},
                {"Reproducciones de canciones que ya no existen", "SELECT COUNT(*) FROM usuario_reproduccion r LEFT JOIN cancion c ON c.id = r.cancion_id WHERE c.id IS NULL"},
                {"Notificaciones de cuentas que ya no existen", "SELECT COUNT(*) FROM notificacion n LEFT JOIN usuario u ON u.id = n.usuario_id WHERE u.id IS NULL"},
                {"Mensajes de cuentas que ya no existen", "SELECT COUNT(*) FROM mensaje m LEFT JOIN usuario u ON u.id = m.de_id WHERE u.id IS NULL"},
                {"Seguimientos de cuentas que ya no existen", "SELECT COUNT(*) FROM seguimiento s LEFT JOIN usuario u ON u.id = s.seguidor_id WHERE u.id IS NULL"},
                {"Playlists de cuentas que ya no existen", "SELECT COUNT(*) FROM playlist p LEFT JOIN usuario u ON u.id = p.usuario_id WHERE p.usuario_id IS NOT NULL AND u.id IS NULL"},
        };
        List<Map<String, Object>> res = new ArrayList<>();
        for (String[] p : pruebas) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("prueba", p[0]);
            long t = System.nanoTime();
            try {
                Long n = jdbc.queryForObject(p[1], Long.class);
                m.put("estado", n == null || n == 0 ? "correcta" : "advertencia");
                m.put("resultado", n == null ? 0 : n);
            } catch (Exception e) {
                m.put("estado", "no_disponible");
                m.put("resultado", Sanitizador.limpiar(e.getClass().getSimpleName(), 80));
            }
            m.put("duracionMs", MonitorService.redondear((System.nanoTime() - t) / 1e6));
            res.add(m);
        }
        integridadCache = Map.of("fecha", LocalDateTime.now().toString(), "pruebas", res,
                "nota", "Solo se cuenta: no se borra ni repara nada");
        return integridadCache;
    }
}
