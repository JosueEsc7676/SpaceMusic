package com.bpm.music.comunidad;

import com.bpm.music.security.CurrentUser;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

/** Gustos, reportes de problemas y resumen del año (para cualquier usuario con sesión). */
@RestController
public class ComunidadController {

    private final ComunidadService service;

    public ComunidadController(ComunidadService service) {
        this.service = service;
    }

    // ── Gustos ──

    @GetMapping("/api/gustos")
    public Map<String, Object> gustos() {
        return service.gustos(CurrentUser.id());
    }

    @GetMapping("/api/gustos/opciones")
    public Map<String, Object> opciones() {
        return service.opciones();
    }

    @PutMapping("/api/gustos")
    @SuppressWarnings("unchecked")
    public Map<String, Object> guardarGustos(@RequestBody Map<String, Object> b) {
        return service.guardarGustos(CurrentUser.id(), lista(b.get("generos")), lista(b.get("artistas")));
    }

    private static List<String> lista(Object o) {
        if (!(o instanceof List<?> l)) return List.of();
        return l.stream().filter(Objects::nonNull).map(String::valueOf).limit(100).toList();
    }

    // ── Reportar un problema ──

    @PostMapping("/api/reportes")
    public Map<String, Object> reportar(@RequestBody Map<String, Object> b) {
        Long id = b.get("cancionId") instanceof Number n ? n.longValue() : null;
        return service.reportar(CurrentUser.id(), id, Objects.toString(b.get("motivo"), null),
                Objects.toString(b.get("detalle"), null), Objects.toString(b.get("cliente"), null));
    }

    // ── Resumen ──

    @GetMapping("/api/resumen")
    public Map<String, Object> resumen(@RequestParam(required = false) Integer anio, @RequestParam(required = false) Integer mes) {
        return service.resumen(CurrentUser.id(), anio == null ? LocalDate.now().getYear() : anio, mes);
    }

    @GetMapping("/api/resumen/periodos")
    public Map<String, Object> periodos() {
        return service.periodos(CurrentUser.id());
    }

    // ── Administración (reportes) ──

    @GetMapping("/api/admin/comunidad/conteos")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> conteos() {
        return service.conteos();
    }

    @GetMapping("/api/admin/comunidad/reportes")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> reportes(@RequestParam(required = false) String estado,
                                        @RequestParam(defaultValue = "0") int pagina, @RequestParam(defaultValue = "25") int tam) {
        Page<ReporteCancion> p = service.listarReportes(validar(estado, ReporteCancion.Estado.class), pagina, tam);
        return pagina(p.map(service::filaReporte));
    }

    @PutMapping("/api/admin/comunidad/reportes/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> responderReporte(@PathVariable Long id, @RequestBody Map<String, Object> b) {
        String estado = validar(Objects.toString(b.get("estado"), null), ReporteCancion.Estado.class);
        if (estado == null) throw new IllegalArgumentException("Falta el estado");
        return service.filaReporte(service.responderReporte(id, estado, Objects.toString(b.get("respuesta"), null)));
    }

    private static <E extends Enum<E>> String validar(String v, Class<E> tipo) {
        if (v == null || v.isBlank()) return null;
        for (E e : tipo.getEnumConstants()) if (e.name().equals(v)) return v;
        throw new IllegalArgumentException("Estado no válido");
    }

    private static Map<String, Object> pagina(Page<Map<String, Object>> p) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("contenido", p.getContent());
        r.put("pagina", p.getNumber());
        r.put("paginas", p.getTotalPages());
        r.put("total", p.getTotalElements());
        return r;
    }
}
