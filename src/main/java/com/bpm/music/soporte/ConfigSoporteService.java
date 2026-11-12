package com.bpm.music.soporte;

import com.bpm.music.repositories.UsuarioRepository;
import com.bpm.music.security.CurrentUser;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ajustes del soporte con valores por defecto y límites válidos. Cada cambio queda en la auditoría
 * (valor anterior → nuevo), así hay historial de cambios de configuración.
 */
@Service
public class ConfigSoporteService {

    /** clave → [por defecto, mínimo, máximo, descripción] */
    static final Map<String, Object[]> AJUSTES = new LinkedHashMap<>();
    static {
        AJUSTES.put("alerta.cpu", new Object[]{90.0, 10.0, 100.0, "CPU del sistema (%) para alertar"});
        AJUSTES.put("alerta.ram", new Object[]{90.0, 10.0, 100.0, "Memoria usada del servidor (%) para alertar"});
        AJUSTES.put("alerta.disco", new Object[]{5.0, 1.0, 50.0, "Disco libre mínimo (%) antes de alertar"});
        AJUSTES.put("alerta.errores5xx", new Object[]{10.0, 1.0, 1000.0, "Errores del servidor por minuto para alertar"});
        AJUSTES.put("alerta.audio", new Object[]{5.0, 1.0, 1000.0, "Fallos de reproducción por minuto para alertar"});
        AJUSTES.put("alerta.login", new Object[]{20.0, 3.0, 1000.0, "Inicios de sesión fallidos por minuto para alertar"});
        AJUSTES.put("alerta.repetirMinutos", new Object[]{30.0, 5.0, 1440.0, "Minutos mínimos entre avisos de una misma alerta"});
        AJUSTES.put("retencion.metricasDias", new Object[]{30.0, 1.0, 365.0, "Días que se guardan las métricas por minuto"});
        AJUSTES.put("retencion.incidenciasDias", new Object[]{90.0, 7.0, 730.0, "Días que se guardan las incidencias cerradas y su conteo diario"});
        AJUSTES.put("retencion.diagnosticosDias", new Object[]{60.0, 7.0, 365.0, "Días que se guarda el historial de diagnósticos y alertas resueltas"});
        AJUSTES.put("respaldo.copias", new Object[]{7.0, 1.0, 60.0, "Copias de seguridad de la base de datos que se conservan"});
        // Organizador automático (hora de El Salvador)
        AJUSTES.put("org.activo", new Object[]{1.0, 0.0, 1.0, "Organizador automático encendido (1) o apagado (0)"});
        AJUSTES.put("org.horaDiaria", new Object[]{3.0, 0.0, 23.0, "Hora de la revisión diaria (canciones nuevas o cambiadas)"});
        AJUSTES.put("org.minutoDiario", new Object[]{15.0, 0.0, 59.0, "Minuto de la revisión diaria"});
        AJUSTES.put("org.diaSemanal", new Object[]{7.0, 1.0, 7.0, "Día de la revisión general (1 = lunes … 7 = domingo)"});
        AJUSTES.put("org.horaSemanal", new Object[]{5.0, 0.0, 23.0, "Hora de la revisión general semanal"});
        AJUSTES.put("org.lote", new Object[]{40.0, 5.0, 200.0, "Canciones por lote (se guarda el avance después de cada lote)"});
        AJUSTES.put("org.maxMinutos", new Object[]{120.0, 10.0, 600.0, "Minutos máximos por ejecución"});
        AJUSTES.put("org.cpuMax", new Object[]{70.0, 20.0, 100.0, "CPU del sistema (%) por encima de la cual el trabajo espera"});
        AJUSTES.put("org.pausaSiReproduce", new Object[]{1.0, 0.0, 1.0, "Esperar mientras alguien escucha música (1 = sí)"});
        AJUSTES.put("org.iaActiva", new Object[]{0.0, 0.0, 1.0, "Etiquetas con IA local (solo si la última evaluación llega al 80 %)"});
        AJUSTES.put("org.iaHilos", new Object[]{1.0, 1.0, 4.0, "Núcleos de CPU que puede usar la IA local"});
    }

    private final ConfigSoporteRepository repo;
    private final AuditoriaService auditoria;
    private final UsuarioRepository usuarios;
    private final Map<String, Double> cache = new ConcurrentHashMap<>();

    public ConfigSoporteService(ConfigSoporteRepository repo, AuditoriaService auditoria, UsuarioRepository usuarios) {
        this.repo = repo;
        this.auditoria = auditoria;
        this.usuarios = usuarios;
    }

    public double valor(String clave) {
        return cache.computeIfAbsent(clave, k -> repo.findById(k).map(c -> {
            try { return Double.parseDouble(c.getValor()); } catch (Exception e) { return (Double) AJUSTES.get(k)[0]; }
        }).orElse((Double) AJUSTES.get(k)[0]));
    }

    public Map<String, Object> todos() {
        return grupo(false);
    }

    /** Ajustes del organizador (los de alertas y retención van en {@link #todos()}). */
    public Map<String, Object> organizador() {
        return grupo(true);
    }

    private Map<String, Object> grupo(boolean org) {
        Map<String, Object> r = new LinkedHashMap<>();
        AJUSTES.forEach((k, d) -> {
            if (k.startsWith("org.") != org) return;
            var fila = repo.findById(k).orElse(null);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("valor", valor(k));
            m.put("porDefecto", d[0]);
            m.put("min", d[1]);
            m.put("max", d[2]);
            m.put("descripcion", d[3]);
            m.put("actualizada", fila == null ? null : fila.getActualizada());
            m.put("actualizadaPor", fila == null ? null : fila.getActualizadaPor());
            r.put(k, m);
        });
        return r;
    }

    public Map<String, Object> cambiar(String clave, Object valor) {
        Object[] d = AJUSTES.get(clave);
        if (d == null) throw new IllegalArgumentException("Ajuste desconocido");
        double v;
        try { v = Double.parseDouble(String.valueOf(valor)); } catch (Exception e) { throw new IllegalArgumentException("Escribe un número"); }
        if (v < (Double) d[1] || v > (Double) d[2]) throw new IllegalArgumentException("El valor debe estar entre " + d[1] + " y " + d[2]);
        double antes = valor(clave);
        ConfigSoporte c = repo.findById(clave).orElseGet(ConfigSoporte::new);
        c.setClave(clave);
        c.setValor(String.valueOf(v));
        c.setActualizada(LocalDateTime.now());
        Long uid = CurrentUser.id();
        c.setActualizadaPor(usuarios.findById(uid).map(u -> u.getUsername()).orElse("#" + uid));
        repo.save(c);
        cache.put(clave, v);
        auditoria.registrar("config.cambiar", clave + ": " + antes + " → " + v, "OK");
        return clave.startsWith("org.") ? organizador() : todos();
    }
}
