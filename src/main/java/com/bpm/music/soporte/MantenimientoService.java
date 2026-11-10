package com.bpm.music.soporte;

import com.bpm.music.services.BibliotecaService;
import com.bpm.music.services.EscanerService;
import com.bpm.music.services.GeneroService;
import com.bpm.music.services.RecomendacionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Tareas de mantenimiento permitidas (sin comandos del sistema, sin SQL libre, sin borrar archivos de música):
 * estado de las tareas en segundo plano, acceso a carpetas, cachés en memoria, versiones, exportación
 * de diagnóstico sin datos sensibles y la limpieza programada de registros antiguos del propio soporte.
 */
@Service
public class MantenimientoService {

    private final EscanerService escaner;
    private final GeneroService generos;
    private final BibliotecaService biblioteca;
    private final RecomendacionService recomendaciones;
    private final DiagnosticoService diagnosticos;
    private final BibliotecaDiagnosticoService revisionBiblioteca;
    private final RespaldoService respaldos;
    private final MonitorService monitor;
    private final IncidenciasService incidencias;
    private final AlertasService alertas;
    private final ConfigSoporteService config;
    private final MetricaRepository metricas;
    private final IncidenciaRepository incRepo;
    private final IncidenciaDiaRepository incDias;
    private final EndpointErrorDiaRepository endpointDias;
    private final DiagnosticoRepository diagRepo;
    private final AlertaRepository alertaRepo;
    private final AuditoriaService auditoria;
    private final Environment env;
    private volatile Map<String, Object> ultimaLimpieza;

    public MantenimientoService(EscanerService escaner, GeneroService generos, BibliotecaService biblioteca,
                                RecomendacionService recomendaciones, DiagnosticoService diagnosticos,
                                BibliotecaDiagnosticoService revisionBiblioteca, RespaldoService respaldos, MonitorService monitor,
                                IncidenciasService incidencias, AlertasService alertas, ConfigSoporteService config,
                                MetricaRepository metricas, IncidenciaRepository incRepo, IncidenciaDiaRepository incDias,
                                EndpointErrorDiaRepository endpointDias, DiagnosticoRepository diagRepo, AlertaRepository alertaRepo,
                                AuditoriaService auditoria, Environment env) {
        this.escaner = escaner;
        this.generos = generos;
        this.biblioteca = biblioteca;
        this.recomendaciones = recomendaciones;
        this.diagnosticos = diagnosticos;
        this.revisionBiblioteca = revisionBiblioteca;
        this.respaldos = respaldos;
        this.monitor = monitor;
        this.incidencias = incidencias;
        this.alertas = alertas;
        this.config = config;
        this.metricas = metricas;
        this.incRepo = incRepo;
        this.incDias = incDias;
        this.endpointDias = endpointDias;
        this.diagRepo = diagRepo;
        this.alertaRepo = alertaRepo;
        this.auditoria = auditoria;
        this.env = env;
    }

    public Map<String, Object> estado() {
        Map<String, Object> r = new LinkedHashMap<>();
        List<Map<String, Object>> tareas = new ArrayList<>();
        Map<String, Object> esc = escaner.estado();
        tareas.add(tarea("Escáner de la biblioteca", Boolean.TRUE.equals(esc.get("escaneando")), "Fase: " + esc.get("fase")
                + (esc.get("ultimoFin") != null ? " · último fin " + esc.get("ultimoFin") : "")));
        tareas.add(tarea("Búsqueda de géneros", generos.estaBuscando(), "Cada 30 minutos busca el género de artistas nuevos"));
        tareas.add(tarea("Diagnóstico", diagnosticos.ocupado(), "Pruebas de soporte"));
        tareas.add(tarea("Revisión de la biblioteca", Boolean.TRUE.equals(revisionBiblioteca.progreso().get("enCurso")),
                String.valueOf(revisionBiblioteca.progreso().getOrDefault("fase", "Sin ejecutar"))));
        Map<String, Object> resp = respaldos.estado();
        tareas.add(tarea("Copia de seguridad", Boolean.TRUE.equals(resp.get("enCurso")), String.valueOf(resp.get("programacion"))));
        MetricaMuestra m = monitor.ultimaMuestra();
        tareas.add(tarea("Monitor del servidor", false, m == null ? "Aún sin muestras" : "Última muestra " + m.getFecha().withNano(0)));
        tareas.add(tarea("Incidencias", false, "En cola para guardar: " + incidencias.resumenGeneral().get("enCola")));
        tareas.add(tarea("Limpieza de registros antiguos", false, ultimaLimpieza == null ? "Todos los días a las 3:40" : "Última: " + ultimaLimpieza));
        r.put("tareas", tareas);
        r.put("directorios", directorios());
        r.put("respaldos", resp);
        r.put("versiones", versiones());
        r.put("ultimaRevisionBiblioteca", revisionBiblioteca.ultima().orElse(null));
        return r;
    }

    private static Map<String, Object> tarea(String nombre, boolean activa, String detalle) {
        return Map.of("nombre", nombre, "estado", activa ? "en curso" : "en espera", "detalle", detalle);
    }

    /** Acceso a las carpetas que usa la app (solo comprueba permisos: no escribe nada). */
    public List<Map<String, Object>> directorios() {
        List<Map<String, Object>> r = new ArrayList<>();
        Map<String, String> carpetas = new LinkedHashMap<>();
        carpetas.put("Música", escaner.raiz().getPath());
        carpetas.put("Imágenes y portadas", env.getProperty("music.playlist-images-path", "./uploads"));
        carpetas.put("Copias de seguridad", env.getProperty("soporte.respaldos.ruta", "./respaldos"));
        carpetas.forEach((n, ruta) -> {
            Path p = Path.of(ruta).toAbsolutePath().normalize();
            File f = p.toFile();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("nombre", n);
            m.put("carpeta", p.getFileName() == null ? ruta : p.getFileName().toString());
            m.put("existe", f.isDirectory());
            m.put("lectura", f.isDirectory() && Files.isReadable(p));
            m.put("escritura", f.isDirectory() && Files.isWritable(p));
            m.put("libreGb", f.isDirectory() ? MonitorService.redondear(f.getUsableSpace() / 1e9) : null);
            r.add(m);
        });
        return r;
    }

    public Map<String, Object> versiones() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("web", leer("static/app/novedades.json", "version"));
        r.put("apkMovil", leer("static/app/version.json", "movil"));
        r.put("apkTv", leer("static/app/version.json", "tv"));
        Map<String, Object> act = monitor.actual();
        r.put("plataforma", act.get("version"));
        return r;
    }

    private static Object leer(String recurso, String campo) {
        try (InputStream in = new ClassPathResource(recurso).getInputStream()) {
            var n = new ObjectMapper().readTree(in).path(campo);
            return n.isMissingNode() ? "No disponible" : new ObjectMapper().convertValue(n, Object.class);
        } catch (Exception e) {
            return "No disponible";
        }
    }

    /** Cachés en memoria que se pueden vaciar sin riesgo (se recalculan solas). */
    public Map<String, Object> limpiarCache(String cual) {
        switch (cual == null ? "" : cual) {
            case "biblioteca" -> biblioteca.invalidar();
            case "recomendaciones" -> recomendaciones.limpiarCaches();
            case "generos" -> generos.limpiarCache();
            default -> throw new IllegalArgumentException("Caché desconocida");
        }
        auditoria.registrar("mantenimiento.cache", "Caché vaciada: " + cual, "OK");
        return Map.of("ok", true, "cache", cual);
    }

    /** Exportación de diagnóstico sin datos sensibles (sin correos, tokens, rutas completas ni trazas). */
    public Map<String, Object> exportar() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("generado", LocalDateTime.now().toString());
        Map<String, Object> act = new LinkedHashMap<>(monitor.actual());
        act.remove("escaner");
        r.put("estado", act);
        r.put("incidencias", incidencias.resumenGeneral());
        r.put("ultimasIncidencias", incidencias.buscar(null, null, null, null, null, null, null, "fecha", 0, 50).getContent());
        r.put("alertas", alertas.resumen());
        r.put("diagnosticos", diagnosticos.historial(0, 10).getContent());
        r.put("tareas", estado().get("tareas"));
        r.put("directorios", directorios());
        r.put("versiones", versiones());
        r.put("ajustes", config.todos());
        return r;
    }

    // ═══════════════════ Retención de registros del soporte ═══════════════════

    @Scheduled(cron = "0 40 3 * * *")
    @Transactional
    public void limpiarAntiguos() {
        Map<String, Object> r = new LinkedHashMap<>();
        LocalDateTime ahora = LocalDateTime.now();
        r.put("metricas", metricas.borrarAnteriores(ahora.minusDays((long) config.valor("retencion.metricasDias"))));
        long diasInc = (long) config.valor("retencion.incidenciasDias");
        r.put("conteosDiarios", incDias.deleteByDiaBefore(LocalDate.now().minusDays(diasInc)));
        r.put("erroresPorRuta", endpointDias.deleteByDiaBefore(LocalDate.now().minusDays(diasInc)));
        long cerradas = 0;
        for (Incidencia.Estado e : List.of(Incidencia.Estado.RESUELTA, Incidencia.Estado.DESCARTADA)) {
            var viejas = incRepo.buscar(null, null, null, e, null, null, ahora.minusDays(diasInc), org.springframework.data.domain.PageRequest.of(0, 500));
            cerradas += viejas.getNumberOfElements();
            incRepo.deleteAll(viejas.getContent());
        }
        r.put("incidenciasCerradas", cerradas);
        long diasDiag = (long) config.valor("retencion.diagnosticosDias");
        r.put("diagnosticos", diagRepo.deleteByInicioBefore(ahora.minusDays(diasDiag)));
        r.put("alertasResueltas", alertaRepo.deleteByEstadoAndResueltaBefore(Alerta.Estado.RESUELTA, ahora.minusDays(diasDiag)));
        ultimaLimpieza = Map.of("fecha", ahora.withNano(0).toString(), "borrados", r);
        auditoria.registrar("mantenimiento.retencion", "Limpieza programada de registros del soporte: " + r, "OK");
    }
}
