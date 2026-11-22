package com.bpm.music.soporte;

import com.bpm.music.models.Usuario;
import com.bpm.music.repositories.UsuarioRepository;
import com.bpm.music.security.CurrentUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Centro de soporte técnico (Administración → Soporte). Solo administradores: lo exige SecurityConfig
 * para /api/admin/** y, además, cada método de esta clase (@PreAuthorize). Las acciones quedan auditadas
 * (AuditoriaInterceptor) y las sensibles tienen límite de frecuencia.
 */
@RestController
@RequestMapping("/api/admin/soporte")
@PreAuthorize("hasRole('ADMIN')")
public class SoporteCentroController {

    private final MonitorService monitor;
    private final IncidenciasService incidencias;
    private final DiagnosticoService diagnosticos;
    private final BibliotecaDiagnosticoService biblioteca;
    private final BaseDatosService baseDatos;
    private final SesionesService sesiones;
    private final AlertasService alertas;
    private final ConfigSoporteService config;
    private final AuditoriaService auditoria;
    private final MantenimientoService mantenimiento;
    private final RespaldoService respaldos;
    private final InformesService informes;
    private final UsuarioRepository usuarios;

    public SoporteCentroController(MonitorService monitor, IncidenciasService incidencias, DiagnosticoService diagnosticos,
                                   BibliotecaDiagnosticoService biblioteca, BaseDatosService baseDatos, SesionesService sesiones,
                                   AlertasService alertas, ConfigSoporteService config, AuditoriaService auditoria,
                                   MantenimientoService mantenimiento, RespaldoService respaldos, InformesService informes,
                                   UsuarioRepository usuarios) {
        this.monitor = monitor;
        this.incidencias = incidencias;
        this.diagnosticos = diagnosticos;
        this.biblioteca = biblioteca;
        this.baseDatos = baseDatos;
        this.sesiones = sesiones;
        this.alertas = alertas;
        this.config = config;
        this.auditoria = auditoria;
        this.mantenimiento = mantenimiento;
        this.respaldos = respaldos;
        this.informes = informes;
        this.usuarios = usuarios;
    }

    // ═══════════════════ Resumen y monitor ═══════════════════

    @GetMapping("/resumen")
    public Map<String, Object> resumen() {
        Map<String, Object> r = new LinkedHashMap<>(monitor.actual());
        r.put("incidencias", incidencias.resumenGeneral());
        r.put("alertas", alertas.resumen());
        r.put("diagnosticoEnCurso", diagnosticos.ocupado());
        r.put("ultimoDiagnostico", diagnosticos.historial(0, 1).getContent().stream().findFirst().orElse(null));
        r.put("sesionesActivas", sesiones.activas(15).size());
        return r;
    }

    @GetMapping("/monitor/rendimiento")
    public List<Map<String, Object>> rendimiento() { return monitor.rendimiento(); }

    @GetMapping("/monitor/historial")
    public List<MetricaMuestra> historial(@RequestParam(defaultValue = "24") int horas) {
        return monitor.historial(horas);
    }

    // ═══════════════════ Diagnóstico ═══════════════════

    @GetMapping("/diagnosticos/pruebas")
    public List<DiagnosticoService.Prueba> pruebas() {
        return DiagnosticoService.PRUEBAS;
    }

    @PostMapping("/diagnosticos")
    public ResponseEntity<Map<String, Object>> diagnosticar(@RequestParam(required = false) String prueba) {
        try {
            return ResponseEntity.ok(diagnosticos.iniciar(prueba));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/diagnosticos/{id}")
    public Map<String, Object> diagnostico(@PathVariable Long id) {
        return diagnosticos.estado(id);
    }

    @GetMapping("/diagnosticos")
    public Map<String, Object> historialDiagnosticos(@RequestParam(defaultValue = "0") int pagina, @RequestParam(defaultValue = "10") int tam) {
        return pagina(diagnosticos.historial(pagina, tam));
    }

    // ═══════════════════ Incidencias ═══════════════════

    @GetMapping("/incidencias")
    public Map<String, Object> incidencias(@RequestParam(required = false) String q, @RequestParam(required = false) String severidad,
                                           @RequestParam(required = false) String categoria, @RequestParam(required = false) String estado,
                                           @RequestParam(required = false) String endpoint, @RequestParam(required = false) String desde,
                                           @RequestParam(required = false) String hasta, @RequestParam(defaultValue = "fecha") String orden,
                                           @RequestParam(defaultValue = "0") int pagina, @RequestParam(defaultValue = "25") int tam) {
        if (q != null && q.length() > 100) throw new IllegalArgumentException("Búsqueda demasiado larga");
        if (severidad != null && !severidad.isBlank() && !Set.of("ERROR", "WARN").contains(severidad)) throw new IllegalArgumentException("Severidad inválida");
        Map<String, Object> r = pagina(incidencias.buscar(q, severidad, categoria, estado, endpoint, fecha(desde, false), fecha(hasta, true), orden, pagina, tam));
        r.put("resumen", incidencias.resumenGeneral());
        return r;
    }

    @GetMapping("/incidencias/{id}")
    public Map<String, Object> incidencia(@PathVariable Long id) {
        return incidencias.detalle(id);
    }

    @PutMapping("/incidencias/{id}/estado")
    public Map<String, Object> estadoIncidencia(@PathVariable Long id, @RequestBody Map<String, String> b) {
        String estado = b.get("estado");
        if (estado == null || Arrays.stream(Incidencia.Estado.values()).noneMatch(e -> e.name().equals(estado)))
            throw new IllegalArgumentException("Estado inválido");
        String nota = b.get("nota");
        if (nota != null && nota.length() > 1000) throw new IllegalArgumentException("La nota es demasiado larga");
        Map<String, Object> r = incidencias.cambiarEstado(id, estado, nota, usuarioActual());
        auditoria.registrar("incidencia.estado", "#" + id + " → " + estado, "OK");
        return r;
    }

    @PostMapping("/incidencias/{id}/notas")
    public Map<String, Object> notaIncidencia(@PathVariable Long id, @RequestBody Map<String, String> b) {
        return incidencias.nota(id, b.get("nota"), usuarioActual());
    }

    // ═══════════════════ Biblioteca musical ═══════════════════

    @PostMapping("/biblioteca/revisar")
    public ResponseEntity<Map<String, Object>> revisarBiblioteca() {
        try {
            return ResponseEntity.ok(biblioteca.iniciar());
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/biblioteca/revision")
    public Map<String, Object> revisionBiblioteca() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("progreso", biblioteca.progreso());
        r.put("ultima", biblioteca.ultima().orElse(null));
        return r;
    }

    // ═══════════════════ Base de datos ═══════════════════

    @GetMapping("/basedatos")
    public Map<String, Object> baseDatos() {
        return baseDatos.estado();
    }

    @PostMapping("/basedatos/integridad")
    public Map<String, Object> integridad() {
        return baseDatos.comprobarIntegridad();
    }

    // ═══════════════════ Usuarios y sesiones ═══════════════════

    @GetMapping("/sesiones")
    public Map<String, Object> sesiones() {
        Map<Long, SesionesService.Actividad> act = sesiones.activas(15);
        Map<Long, Usuario> us = new HashMap<>();
        usuarios.findAllById(act.keySet()).forEach(u -> us.put(u.getId(), u));
        List<Map<String, Object>> activas = new ArrayList<>();
        act.forEach((uid, a) -> {
            Usuario u = us.get(uid);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", uid);
            m.put("username", u == null ? "#" + uid : u.getUsername());
            m.put("nombre", u == null ? "" : u.getNombreVisible());
            m.put("ultimaActividad", Instant.ofEpochMilli(a.ultima()).toString());
            m.put("ultimoLogin", u == null ? null : u.getUltimoLogin());
            m.put("cliente", a.cliente());
            m.put("version", a.version());
            m.put("redes", a.ips());
            m.put("peticiones", a.peticiones());
            activas.add(m);
        });
        activas.sort(Comparator.comparing((Map<String, Object> m) -> (String) m.get("ultimaActividad")).reversed());
        Map<String, Long> clientes = new TreeMap<>();
        act.values().forEach(a -> clientes.merge(a.cliente() + (a.version() != null ? " " + a.version() : ""), 1L, Long::sum));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("activas", activas);
        r.put("clientes", clientes);
        r.put("fallosAcceso", sesiones.fallos().stream().limit(100).toList());
        r.put("fallosLogin", sesiones.fallosLogin().stream().limit(100).toList());
        r.put("sospechosas", sesiones.sospechosas());
        r.put("nota", "Sesiones = cuentas con actividad en los últimos 15 minutos (las sesiones usan tokens sin estado). "
                + "Las IPs se muestran recortadas y no se guarda contenido privado.");
        return r;
    }

    // ═══════════════════ Alertas ═══════════════════

    @GetMapping("/alertas")
    public Map<String, Object> listaAlertas(@RequestParam(required = false) String estado, @RequestParam(defaultValue = "0") int pagina,
                                            @RequestParam(defaultValue = "25") int tam) {
        Map<String, Object> r = pagina(alertas.listar(estado, pagina, tam));
        r.put("resumen", alertas.resumen());
        return r;
    }

    @PostMapping("/alertas/{id}/reconocer")
    public Alerta reconocer(@PathVariable Long id) {
        return alertas.reconocer(id, usuarioActual());
    }

    @PostMapping("/alertas/{id}/resolver")
    public Alerta resolverAlerta(@PathVariable Long id) {
        return alertas.resolver(id);
    }

    @GetMapping("/config")
    public Map<String, Object> ajustes() {
        return config.todos();
    }

    @PutMapping("/config")
    public Map<String, Object> cambiarAjuste(@RequestBody Map<String, Object> b) {
        Object clave = b.get("clave");
        if (!(clave instanceof String c) || c.length() > 60) throw new IllegalArgumentException("Ajuste inválido");
        return config.cambiar(c, b.get("valor"));
    }

    // ═══════════════════ Seguridad y auditoría ═══════════════════

    @GetMapping("/auditoria")
    public Map<String, Object> auditoria(@RequestParam(required = false) String q, @RequestParam(required = false) String resultado,
                                         @RequestParam(required = false) String desde, @RequestParam(defaultValue = "0") int pagina,
                                         @RequestParam(defaultValue = "30") int tam) {
        if (q != null && q.length() > 100) throw new IllegalArgumentException("Búsqueda demasiado larga");
        if (resultado != null && !resultado.isBlank() && !Set.of("OK", "ERROR", "DENEGADA").contains(resultado)) throw new IllegalArgumentException("Resultado inválido");
        Map<String, Object> r = pagina(auditoria.buscar(q, resultado, fecha(desde, false), pagina, tam));
        r.put("medidas", List.of(
                "Solo administradores: lo comprueba el servidor en cada ruta /api/admin (no solo se ocultan botones).",
                "Cada acción administrativa queda registrada con usuario, fecha, resultado e IP parcial; el registro no se puede editar ni borrar desde la app.",
                "Límite de frecuencia para diagnósticos, mantenimiento, copias de seguridad, exportación y avisos masivos.",
                "Los registros se limpian de tokens, contraseñas, cookies y correos antes de guardarse.",
                "CSRF: la API no usa cookies de sesión (el token va en la cabecera Authorization), por lo que no aplica.",
                "Desactivar una cuenta, cambiar su rol o cerrar sus sesiones invalida sus tokens al instante."));
        return r;
    }

    // ═══════════════════ Mantenimiento ═══════════════════

    @GetMapping("/mantenimiento")
    public Map<String, Object> mantenimiento() {
        return mantenimiento.estado();
    }

    @PostMapping("/mantenimiento/cache")
    public Map<String, Object> limpiarCache(@RequestParam String cual) {
        return mantenimiento.limpiarCache(cual);
    }

    @PostMapping("/respaldos")
    public ResponseEntity<Map<String, Object>> respaldar() {
        return respaldos.iniciar() ? ResponseEntity.ok(Map.of("iniciado", true))
                : ResponseEntity.status(409).body(Map.of("error", "Ya hay una copia de seguridad en curso"));
    }

    @GetMapping("/exportar")
    public ResponseEntity<byte[]> exportar() throws Exception {
        byte[] json = new ObjectMapper().findAndRegisterModules().writerWithDefaultPrettyPrinter().writeValueAsBytes(mantenimiento.exportar());
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"spacemusic-diagnostico-" + LocalDate.now() + ".json\"")
                .body(json);
    }

    // ═══════════════════ Informes ═══════════════════

    @GetMapping("/informes")
    public Map<String, Object> informe(@RequestParam(defaultValue = "7") int dias) {
        return informes.informe(dias);
    }

    @GetMapping("/informes/csv")
    public ResponseEntity<byte[]> csv(@RequestParam String tipo, @RequestParam(defaultValue = "30") int dias) {
        byte[] b = informes.csv(tipo, dias).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"spacemusic-" + tipo + "-" + LocalDate.now() + ".csv\"")
                .body(b);
    }

    // ═══════════════════ Ayudas ═══════════════════

    private String usuarioActual() {
        Long uid = CurrentUser.id();
        return usuarios.findById(uid).map(Usuario::getUsername).orElse("#" + uid);
    }

    private static Map<String, Object> pagina(Page<?> p) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("contenido", p.getContent());
        r.put("pagina", p.getNumber());
        r.put("tam", p.getSize());
        r.put("total", p.getTotalElements());
        r.put("paginas", p.getTotalPages());
        return r;
    }

    private static LocalDateTime fecha(String s, boolean finDelDia) {
        if (s == null || s.isBlank()) return null;
        try {
            LocalDate d = LocalDate.parse(s.trim());
            return finDelDia ? d.plusDays(1).atStartOfDay().minusNanos(1) : d.atStartOfDay();
        } catch (Exception e) {
            throw new IllegalArgumentException("Fecha inválida (usa AAAA-MM-DD)");
        }
    }
}
