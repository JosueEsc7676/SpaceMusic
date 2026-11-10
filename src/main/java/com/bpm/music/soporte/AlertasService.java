package com.bpm.music.soporte;

import com.bpm.music.models.Usuario;
import com.bpm.music.repositories.UsuarioRepository;
import com.bpm.music.services.MailService;
import com.bpm.music.services.NotificacionesService;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Alertas administrativas a partir de las métricas reales del monitor: CPU, memoria, disco, errores,
 * base de datos, almacenamiento, reproducción, inicios de sesión fallidos e incidencias que reaparecen.
 * Una misma condición actualiza su alerta (no se duplican) y se avisa a los administradores por la campana
 * como máximo una vez cada "alerta.repetirMinutos". Cuando la condición desaparece, la alerta se resuelve sola.
 */
@Service
public class AlertasService {

    private static final List<Alerta.Estado> ABIERTAS = List.of(Alerta.Estado.ACTIVA, Alerta.Estado.RECONOCIDA);

    private final AlertaRepository repo;
    private final MonitorService monitor;
    private final ConfigSoporteService config;
    private final NotificacionesService notificaciones;
    private final UsuarioRepository usuarios;
    private final MailService mail;
    private final AuditoriaService auditoria;

    public AlertasService(AlertaRepository repo, MonitorService monitor, ConfigSoporteService config,
                          NotificacionesService notificaciones, UsuarioRepository usuarios, MailService mail, AuditoriaService auditoria) {
        this.repo = repo;
        this.monitor = monitor;
        this.config = config;
        this.notificaciones = notificaciones;
        this.usuarios = usuarios;
        this.mail = mail;
        this.auditoria = auditoria;
    }

    /** Cada minuto, después de la muestra del monitor. */
    @Scheduled(initialDelay = 45_000, fixedRate = 60_000)
    public void evaluar() {
        MetricaMuestra m = monitor.ultimaMuestra();
        if (m == null) return;
        Map<String, String[]> activas = new LinkedHashMap<>(); // clave → [tipo, severidad, mensaje]
        if (m.getCpuSistema() != null && m.getCpuSistema() >= config.valor("alerta.cpu"))
            activas.put("cpu", new String[]{"cpu", "advertencia", "CPU del sistema al " + m.getCpuSistema() + " %"});
        if (m.getMemoriaUsadaMb() != null && m.getMemoriaMaxMb() != null && m.getMemoriaMaxMb() > 0
                && 100.0 * m.getMemoriaUsadaMb() / m.getMemoriaMaxMb() >= config.valor("alerta.ram"))
            activas.put("ram", new String[]{"ram", "advertencia", "Memoria de SpaceMusic (Java) al " + Math.round(100.0 * m.getMemoriaUsadaMb() / m.getMemoriaMaxMb()) + " %"});
        if (m.getDiscoLibreGb() != null && m.getDiscoTotalGb() != null && m.getDiscoTotalGb() > 0
                && 100.0 * m.getDiscoLibreGb() / m.getDiscoTotalGb() < config.valor("alerta.disco"))
            activas.put("disco", new String[]{"disco", "critica", "Quedan " + m.getDiscoLibreGb() + " GB libres en el disco de la música"});
        if (m.getErrores5xx() != null && m.getErrores5xx() >= config.valor("alerta.errores5xx"))
            activas.put("errores", new String[]{"errores", "critica", m.getErrores5xx() + " errores del servidor en el último minuto"});
        if (!Boolean.TRUE.equals(m.getDbOk()))
            activas.put("db", new String[]{"db", "critica", "La base de datos no responde"});
        if (!Boolean.TRUE.equals(m.getAlmacenamientoOk()))
            activas.put("almacenamiento", new String[]{"almacenamiento", "critica", "No se puede leer la carpeta de música"});
        if (m.getFallosAudio() != null && m.getFallosAudio() >= config.valor("alerta.audio"))
            activas.put("audio", new String[]{"audio", "advertencia", m.getFallosAudio() + " fallos de reproducción en el último minuto"});
        if (m.getFallosLogin() != null && m.getFallosLogin() >= config.valor("alerta.login"))
            activas.put("login", new String[]{"seguridad", "advertencia", m.getFallosLogin() + " inicios de sesión fallidos en el último minuto"});
        activas.forEach((clave, v) -> levantar(clave, v[0], v[1], v[2]));
        // Lo que ya no ocurre se resuelve solo (las de incidencias se cierran a mano)
        for (Alerta a : repo.findByEstadoIn(ABIERTAS)) {
            if (!a.getClave().startsWith("incidencia:") && !activas.containsKey(a.getClave())) {
                a.setEstado(Alerta.Estado.RESUELTA);
                a.setResuelta(LocalDateTime.now());
                a.setActualizada(LocalDateTime.now());
                repo.save(a);
            }
        }
    }

    @EventListener
    public void reaparicion(IncidenciasService.Reaparicion r) {
        levantar("incidencia:" + r.id(), "incidencia", "advertencia", "Reapareció la incidencia #" + r.id() + ": " + Sanitizador.limpiar(r.mensaje(), 200));
    }

    void levantar(String clave, String tipo, String severidad, String mensaje) {
        LocalDateTime ahora = LocalDateTime.now();
        Alerta a = repo.findFirstByClaveAndEstadoIn(clave, ABIERTAS).orElse(null);
        if (a == null) {
            a = new Alerta();
            a.setClave(clave);
            a.setTipo(tipo);
            a.setCreada(ahora);
            a.setEstado(Alerta.Estado.ACTIVA);
        }
        a.setSeveridad(severidad);
        a.setMensaje(mensaje);
        a.setOcurrencias(a.getOcurrencias() + 1);
        a.setActualizada(ahora);
        // Aviso en la campana de los administradores (control de frecuencia; las reconocidas no se repiten)
        long cada = (long) config.valor("alerta.repetirMinutos");
        if (a.getEstado() == Alerta.Estado.ACTIVA && (a.getAvisada() == null || a.getAvisada().isBefore(ahora.minusMinutes(cada)))) {
            for (Usuario u : usuarios.findAll()) {
                if (u.getRol() == Usuario.Rol.ADMIN && u.isActivo())
                    notificaciones.crear(u.getId(), "ALERTA", null, "Soporte: " + mensaje, "#/soporte?sec=alertas");
            }
            a.setAvisada(ahora);
        }
        repo.save(a);
    }

    public Page<Alerta> listar(String estado, int pagina, int tam) {
        Collection<Alerta.Estado> estados = estado == null || estado.isBlank() ? List.of(Alerta.Estado.values())
                : List.of(Alerta.Estado.valueOf(estado));
        return repo.findByEstadoInOrderByActualizadaDesc(estados, PageRequest.of(Math.max(0, pagina), Math.min(Math.max(tam, 1), 100)));
    }

    public Map<String, Object> resumen() {
        Map<String, Object> r = new LinkedHashMap<>();
        for (Alerta.Estado e : Alerta.Estado.values()) r.put(e.name(), repo.countByEstado(e));
        r.put("ultimas24h", repo.countByCreadaAfter(LocalDateTime.now().minusHours(24)));
        // Integraciones de aviso: solo la campana está activa; el correo depende de la configuración del servidor
        r.put("canales", Map.of("campana", "activo", "correo", mail.configurado() ? "configurado (no se usa para alertas)" : "pendiente de configuración"));
        return r;
    }

    public Alerta reconocer(Long id, String usuario) {
        Alerta a = repo.findById(id).orElseThrow(() -> new NoSuchElementException("Alerta no encontrada"));
        if (a.getEstado() != Alerta.Estado.ACTIVA) throw new IllegalArgumentException("La alerta no está activa");
        a.setEstado(Alerta.Estado.RECONOCIDA);
        a.setReconocidaPor(usuario);
        a.setReconocidaEn(LocalDateTime.now());
        a.setActualizada(LocalDateTime.now());
        auditoria.registrar("alerta.reconocer", "#" + id + " " + a.getMensaje(), "OK");
        return repo.save(a);
    }

    public Alerta resolver(Long id) {
        Alerta a = repo.findById(id).orElseThrow(() -> new NoSuchElementException("Alerta no encontrada"));
        a.setEstado(Alerta.Estado.RESUELTA);
        a.setResuelta(LocalDateTime.now());
        a.setActualizada(LocalDateTime.now());
        auditoria.registrar("alerta.resolver", "#" + id + " " + a.getMensaje(), "OK");
        return repo.save(a);
    }
}
