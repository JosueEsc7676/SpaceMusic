package com.bpm.music.soporte;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import com.bpm.music.services.RegistroErrores;
import jakarta.annotation.PostConstruct;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Centro de incidencias: recibe las advertencias y errores que el servidor ya registra (sin cambiar cómo
 * se producen), los limpia de datos sensibles y los agrupa por huella en la base de datos.
 * La escritura es por lotes cada 10 s, fuera del hilo que registró el error.
 */
@Service
public class IncidenciasService {

    /** Evento publicado cuando una incidencia marcada como resuelta vuelve a ocurrir. */
    public record Reaparicion(Long id, String mensaje) {}

    private record Evento(long t, String nivel, String logger, String mensaje, String tipo, String traza, String rid, String ruta) {}

    private final IncidenciaRepository repo;
    private final IncidenciaDiaRepository dias;
    private final ApplicationEventPublisher eventos;
    private final ConcurrentLinkedQueue<Evento> pendientes = new ConcurrentLinkedQueue<>();
    private final AtomicInteger enCola = new AtomicInteger();
    private final AtomicInteger descartados = new AtomicInteger();
    private static final ThreadLocal<Boolean> GUARDANDO = ThreadLocal.withInitial(() -> false);
    private static final int MAX_COLA = 2000;

    public IncidenciasService(IncidenciaRepository repo, IncidenciaDiaRepository dias, ApplicationEventPublisher eventos) {
        this.repo = repo;
        this.dias = dias;
        this.eventos = eventos;
    }

    @PostConstruct
    void conectar() {
        RegistroErrores.incidencias = this::recibir;
    }

    void recibir(ILoggingEvent e) {
        // Lo que se registra mientras se guardan incidencias no se vuelve a capturar (evita bucles)
        if (GUARDANDO.get()) return;
        if (enCola.get() >= MAX_COLA) { descartados.incrementAndGet(); return; }
        IThrowableProxy t = e.getThrowableProxy();
        String traza = null, tipo = null;
        if (t != null) {
            tipo = t.getClassName();
            StringBuilder sb = new StringBuilder(t.getClassName()).append(": ").append(t.getMessage());
            int n = 0;
            for (StackTraceElementProxy el : t.getStackTraceElementProxyArray()) {
                if (n++ >= 40) { sb.append("\n\t…"); break; }
                sb.append("\n\tat ").append(el.getStackTraceElement());
            }
            for (IThrowableProxy c = t.getCause(); c != null && n < 60; c = c.getCause(), n += 5) {
                sb.append("\nCausado por: ").append(c.getClassName()).append(": ").append(c.getMessage());
                if (tipo.startsWith("jakarta.servlet") || tipo.startsWith("org.springframework.web")) tipo = c.getClassName();
            }
            traza = Sanitizador.limpiar(sb.toString(), 8000);
        }
        Map<String, String> mdc = e.getMDCPropertyMap();
        pendientes.add(new Evento(e.getTimeStamp(), e.getLevel().toString(), e.getLoggerName(),
                Sanitizador.limpiar(e.getFormattedMessage(), 600), tipo, traza,
                mdc == null ? null : mdc.get("rid"), mdc == null ? null : mdc.get("ruta")));
        enCola.incrementAndGet();
    }

    // ═══════════════════ Guardado por lotes ═══════════════════

    @Scheduled(initialDelay = 15_000, fixedDelay = 10_000)
    public void guardar() {
        if (pendientes.isEmpty()) return;
        GUARDANDO.set(true);
        try {
            Map<String, List<Evento>> grupos = new LinkedHashMap<>();
            for (Evento ev; (ev = pendientes.poll()) != null; ) {
                enCola.decrementAndGet();
                grupos.computeIfAbsent(huella(ev), k -> new ArrayList<>()).add(ev);
            }
            grupos.forEach(this::guardarGrupo);
        } catch (Exception ignored) {
            // Si la base de datos no responde, los eventos de este lote se pierden (no se reintenta en bucle)
        } finally {
            GUARDANDO.set(false);
        }
    }

    void guardarGrupo(String huella, List<Evento> evs) {
        Evento ultimo = evs.get(evs.size() - 1);
        LocalDateTime cuando = fecha(ultimo.t());
        Incidencia i = repo.findByHuella(huella).orElse(null);
        boolean reaparece = false;
        if (i == null) {
            i = new Incidencia();
            i.setHuella(huella);
            i.setSeveridad(ultimo.nivel());
            i.setCategoria(categoria(ultimo.logger(), ultimo.tipo()));
            i.setOrigen(corto(ultimo.logger()));
            i.setTipo(ultimo.tipo());
            i.setMensaje(ultimo.mensaje());
            i.setPrimera(fecha(evs.get(0).t()));
            i.setEstado(Incidencia.Estado.NUEVA);
        } else if (i.getEstado() == Incidencia.Estado.RESUELTA) {
            // Volvió a pasar después de marcarse como resuelta: vuelve a "Nueva" y se avisa
            i.setEstado(Incidencia.Estado.NUEVA);
            i.setReaparecio(true);
            reaparece = true;
        }
        i.setRepeticiones(i.getRepeticiones() + evs.size());
        i.setUltima(cuando);
        if (ultimo.ruta() != null) i.setEndpoint(ultimo.ruta());
        if (ultimo.rid() != null) i.setRequestId(ultimo.rid());
        if (ultimo.traza() != null) i.setTraza(ultimo.traza());
        i.setActualizada(LocalDateTime.now());
        i = repo.save(i);
        // Conteo por día
        Map<LocalDate, Integer> porDia = new HashMap<>();
        for (Evento ev : evs) porDia.merge(fecha(ev.t()).toLocalDate(), 1, Integer::sum);
        for (var d : porDia.entrySet()) {
            Long id = i.getId();
            IncidenciaDia fila = dias.findByIncidenciaIdAndDia(id, d.getKey()).orElseGet(() -> {
                IncidenciaDia x = new IncidenciaDia();
                x.setIncidenciaId(id); x.setDia(d.getKey());
                return x;
            });
            fila.setCategoria(i.getCategoria());
            fila.setSeveridad(i.getSeveridad());
            fila.setCuenta(fila.getCuenta() + d.getValue());
            dias.save(fila);
        }
        if (reaparece) eventos.publishEvent(new Reaparicion(i.getId(), i.getMensaje()));
    }

    // ═══════════════════ Consultas ═══════════════════

    public Page<Map<String, Object>> buscar(String q, String severidad, String categoria, String estado, String endpoint,
                                            LocalDateTime desde, LocalDateTime hasta, String orden, int pagina, int tam) {
        Sort sort = switch (orden == null ? "" : orden) {
            case "frecuencia" -> Sort.by(Sort.Direction.DESC, "repeticiones").and(Sort.by(Sort.Direction.DESC, "ultima"));
            case "prioridad" -> Sort.by(Sort.Direction.ASC, "severidad").and(Sort.by(Sort.Direction.DESC, "repeticiones"));
            case "antiguas" -> Sort.by(Sort.Direction.ASC, "ultima");
            default -> Sort.by(Sort.Direction.DESC, "ultima");
        };
        Incidencia.Estado est = null;
        if (estado != null && !estado.isBlank()) est = Incidencia.Estado.valueOf(estado);
        String like = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
        return repo.buscar(like, vacio(severidad), vacio(categoria), est, vacio(endpoint), desde, hasta,
                PageRequest.of(Math.max(0, pagina), Math.min(Math.max(tam, 1), 100), sort)).map(i -> resumen(i, false));
    }

    public Map<String, Object> detalle(Long id) {
        return resumen(repo.findById(id).orElseThrow(() -> new NoSuchElementException("Incidencia no encontrada")), true);
    }

    @Transactional
    public Map<String, Object> cambiarEstado(Long id, String estado, String nota, String usuario) {
        Incidencia i = repo.findById(id).orElseThrow(() -> new NoSuchElementException("Incidencia no encontrada"));
        Incidencia.Estado nuevo = Incidencia.Estado.valueOf(estado);
        Incidencia.Estado antes = i.getEstado();
        i.setEstado(nuevo);
        if (nuevo == Incidencia.Estado.RESUELTA) i.setResueltaEn(LocalDateTime.now());
        if (nota != null && !nota.isBlank()) agregar(i, usuario, "Estado " + antes + " → " + nuevo + ": " + nota.trim());
        else agregar(i, usuario, "Estado " + antes + " → " + nuevo);
        i.setActualizada(LocalDateTime.now());
        return resumen(repo.save(i), true);
    }

    @Transactional
    public Map<String, Object> nota(Long id, String nota, String usuario) {
        Incidencia i = repo.findById(id).orElseThrow(() -> new NoSuchElementException("Incidencia no encontrada"));
        if (nota == null || nota.isBlank() || nota.length() > 1000) throw new IllegalArgumentException("Escribe una nota de hasta 1000 caracteres");
        agregar(i, usuario, nota.trim());
        i.setActualizada(LocalDateTime.now());
        return resumen(repo.save(i), true);
    }

    public Map<String, Object> resumenGeneral() {
        Map<String, Object> r = new LinkedHashMap<>();
        for (Incidencia.Estado e : Incidencia.Estado.values()) r.put(e.name(), repo.countByEstado(e));
        r.put("enCola", enCola.get());
        r.put("descartadasPorExceso", descartados.get());
        r.put("categorias", repo.categorias());
        return r;
    }

    private void agregar(Incidencia i, String usuario, String texto) {
        String linea = "[" + LocalDateTime.now().withNano(0) + " · " + usuario + "] " + Sanitizador.limpiar(texto, 1000);
        String n = i.getNotas() == null ? linea : i.getNotas() + "\n" + linea;
        i.setNotas(n.length() > 20000 ? n.substring(n.length() - 20000) : n);
    }

    private Map<String, Object> resumen(Incidencia i, boolean completo) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.getId());
        m.put("severidad", i.getSeveridad());
        m.put("categoria", i.getCategoria());
        m.put("origen", i.getOrigen());
        m.put("tipo", i.getTipo());
        m.put("mensaje", i.getMensaje());
        m.put("endpoint", i.getEndpoint());
        m.put("requestId", i.getRequestId());
        m.put("primera", i.getPrimera());
        m.put("ultima", i.getUltima());
        m.put("repeticiones", i.getRepeticiones());
        m.put("estado", i.getEstado());
        m.put("reaparecio", Boolean.TRUE.equals(i.getReaparecio()));
        m.put("actualizada", i.getActualizada());
        if (completo) {
            m.put("traza", i.getTraza());
            m.put("notas", i.getNotas());
            m.put("resueltaEn", i.getResueltaEn());
        }
        return m;
    }

    // ═══════════════════ Agrupación ═══════════════════

    /** Huella: mismo nivel, origen, tipo y mensaje (sin números ni textos variables) → misma incidencia. */
    static String huella(Evento ev) {
        return huella(ev.nivel(), ev.logger(), ev.tipo(), ev.mensaje());
    }

    static String huella(String nivel, String logger, String tipo, String mensaje) {
        String msg = mensaje == null ? "" : mensaje
                .replaceAll("'[^']*'|\"[^\"]*\"", "?")
                .replaceAll("\\b[0-9a-f]{8,}\\b", "#")
                .replaceAll("\\d+", "#");
        String base = nivel + "|" + logger + "|" + (tipo == null ? "" : tipo) + "|" + msg;
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(base.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h).substring(0, 40);
        } catch (Exception e) {
            return Integer.toHexString(base.hashCode());
        }
    }

    static String categoria(String logger, String tipo) {
        String l = (logger == null ? "" : logger) + " " + (tipo == null ? "" : tipo);
        String m = l.toLowerCase(Locale.ROOT);
        if (m.contains("hibernate") || m.contains("jdbc") || m.contains("hikari") || m.contains("sql") || m.contains("dataaccess") || m.contains("persistence")) return "base de datos";
        if (m.contains(".security") || m.contains("jwt") || m.contains("auth")) return "seguridad";
        if (m.contains("escaner") || m.contains("metadata") || m.contains("biblioteca") || m.contains("genero") || m.contains("letras") || m.contains("imagen") || m.contains("jaudiotagger")) return "biblioteca";
        if (m.contains("mail")) return "correo";
        if (m.contains("eventos") || m.contains("sse") || m.contains("async")) return "tiempo real";
        if (m.contains(".controllers") || m.contains("globalexceptionhandler") || m.contains("org.springframework.web")) return "api";
        if (m.contains("com.bpm.music")) return "servicios";
        return "sistema";
    }

    private static String corto(String logger) {
        if (logger == null) return "";
        return logger.substring(logger.lastIndexOf('.') + 1);
    }

    private static LocalDateTime fecha(long t) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(t), ZoneId.systemDefault());
    }

    private static String vacio(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
