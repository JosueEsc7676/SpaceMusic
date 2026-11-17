package com.bpm.music.organizador;

import com.bpm.music.externo.Proveedores;
import com.bpm.music.repositories.UsuarioRepository;
import com.bpm.music.security.CurrentUser;
import com.bpm.music.services.BibliotecaService;
import com.bpm.music.soporte.ConfigSoporteService;
import com.bpm.music.soporte.MonitorService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Administración → Organización (solo administradores; cada cambio queda en la auditoría de /api/admin). */
@RestController
@RequestMapping("/api/admin/organizacion")
@PreAuthorize("hasRole('ADMIN')")
public class OrganizacionController {

    private final OrganizadorService organizador;
    private final CompletadoService completado;
    private final SenalesExternasService senales;
    private final AudioAnalisisService audio;
    private final IaLocalService ia;
    private IaEnLineaService enLinea;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setEnLinea(IaEnLineaService enLinea) { this.enLinea = enLinea; }
    private final Proveedores proveedores;
    private final MonitorService monitor;
    private final ConfigSoporteService config;
    private final CambioMetadatoRepository cambios;
    private final EtiquetaCancionRepository etiquetas;
    private final CancionExternaRepository vinculos;
    private final BibliotecaService biblioteca;
    private final UsuarioRepository usuarios;

    public OrganizacionController(OrganizadorService organizador, CompletadoService completado, SenalesExternasService senales,
                                  AudioAnalisisService audio, IaLocalService ia, Proveedores proveedores, MonitorService monitor,
                                  ConfigSoporteService config, CambioMetadatoRepository cambios, EtiquetaCancionRepository etiquetas,
                                  CancionExternaRepository vinculos, BibliotecaService biblioteca, UsuarioRepository usuarios) {
        this.organizador = organizador;
        this.completado = completado;
        this.senales = senales;
        this.audio = audio;
        this.ia = ia;
        this.proveedores = proveedores;
        this.monitor = monitor;
        this.config = config;
        this.cambios = cambios;
        this.etiquetas = etiquetas;
        this.vinculos = vinculos;
        this.biblioteca = biblioteca;
        this.usuarios = usuarios;
    }

    private String quien() {
        Long id = CurrentUser.id();
        return usuarios.findById(id).map(u -> "@" + u.getUsername()).orElse("#" + id);
    }

    @GetMapping
    public Map<String, Object> resumen() {
        Map<String, Object> r = new LinkedHashMap<>(organizador.estado());
        r.put("ia", ia.estado());
        r.put("iaEnLinea", organizador.estadoIaEnLinea());
        r.put("iaProveedorEnLinea", organizador.iaEnLinea());
        r.put("modeloActivo", organizador.modeloActivo());
        r.put("proveedores", proveedores.resumen());
        r.put("vinculos", senales.resumen());
        r.put("metadatos", completado.resumen());
        r.put("audio", audio.resumen());
        r.put("etiquetasIa", Map.of("propuestas", etiquetas.countByOrigenAndEstado("ia", EtiquetaCancion.Estado.PROPUESTA),
                "aceptadas", etiquetas.countByOrigenAndEstado("ia", EtiquetaCancion.Estado.ACEPTADA)));
        r.put("rendimiento", monitor.rendimiento().stream().limit(12).toList());
        r.put("audiosActivos", monitor.audiosActivos());
        return r;
    }

    // ── Trabajos ──

    @PostMapping("/iniciar")
    public TrabajoOrganizador iniciar() {
        return organizador.iniciar("manual", quien());
    }

    @PostMapping("/pausar")
    public TrabajoOrganizador pausar() { return organizador.pausar(); }

    @PostMapping("/reanudar")
    public TrabajoOrganizador reanudar() { return organizador.reanudar(); }

    @PostMapping("/cancelar")
    public TrabajoOrganizador cancelar() { return organizador.cancelar(); }

    @PutMapping("/ajustes")
    public Map<String, Object> ajuste(@RequestBody Map<String, Object> b) {
        String clave = Objects.toString(b.get("clave"), "");
        if (!clave.startsWith("org.")) throw new IllegalArgumentException("Ajuste no válido");
        if (clave.equals("org.iaActiva") && String.valueOf(b.get("valor")).startsWith("1")) {
            var ev = organizador.ultimaEvaluacion();
            if (ev == null || !Boolean.TRUE.equals(ev.get("apta")) || !organizador.modeloActivo().equals(ev.get("modelo")))
                throw new IllegalArgumentException("Primero evalúa el modelo: necesita al menos 80 % de aciertos");
        }
        return config.cambiar(clave, b.get("valor"));
    }

    @PutMapping("/modelo")
    public Map<String, Object> modelo(@RequestBody Map<String, String> b) {
        organizador.cambiarModelo(b.get("nombre"), quien());
        return Map.of("modelo", organizador.modelo());
    }

    /** Clave de la IA en línea: se guarda en un archivo del servidor (nunca se devuelve ni va a la base de datos). */
    @PutMapping("/ia-clave")
    public Map<String, Object> claveIa(@RequestBody Map<String, String> b) {
        enLinea.guardarClave(b.get("clave"));
        return Map.of("configurada", true);
    }

    @DeleteMapping("/ia-clave")
    public Map<String, Object> borrarClaveIa() {
        enLinea.borrarClave();
        return Map.of("configurada", enLinea.configurada());
    }

    @PostMapping("/evaluar")
    public Map<String, Object> evaluar() {
        if (!organizador.evaluarEnSegundoPlano(quien())) throw new IllegalStateException("Ya hay una evaluación o un trabajo en curso");
        return Map.of("iniciada", true);
    }

    // ── Cambios propuestos, aplicados y revertidos ──

    @GetMapping("/cambios")
    public Map<String, Object> cambios(@RequestParam(required = false) String estado, @RequestParam(required = false) String campo,
                                       @RequestParam(defaultValue = "0") int pagina) {
        if (estado != null && !estado.isBlank() && Arrays.stream(CambioMetadato.Estado.values()).noneMatch(e -> e.name().equals(estado)))
            throw new IllegalArgumentException("Estado no válido");
        if (campo != null && !campo.isBlank() && !campo.matches("[a-z]{3,15}")) throw new IllegalArgumentException("Campo no válido");
        Page<CambioMetadato> p = completado.listar(estado, campo, pagina);
        List<Map<String, Object>> filas = new ArrayList<>();
        for (CambioMetadato c : p.getContent()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("campo", c.getCampo());
            m.put("estado", c.getEstado());
            m.put("fuente", c.getFuente());
            m.put("metodo", c.getMetodo());
            m.put("detalle", c.getDetalle());
            m.put("fecha", c.getFecha());
            m.put("resueltoPor", c.getResueltoPor());
            m.put("cancion", biblioteca.porId(c.getCancionId()).orElse(null));
            m.put("anterior", corto(c.getValorAnterior()));
            m.put("nuevo", c.getCampo().equals("portada") ? null : corto(c.getValorNuevo()));
            m.put("imagen", c.getCampo().equals("portada") && completado.archivoPropuesta(c) != null);
            filas.add(m);
        }
        return Map.of("contenido", filas, "pagina", p.getNumber(), "paginas", p.getTotalPages(), "total", p.getTotalElements());
    }

    private static String corto(String s) {
        if (s == null) return null;
        return s.length() > 600 ? s.substring(0, 600) + "…" : s;
    }

    @PostMapping("/cambios/{id}/{accion}")
    public Map<String, Object> accion(@PathVariable Long id, @PathVariable String accion) throws Exception {
        CambioMetadato c = cambios.findById(id).orElseThrow(() -> new NoSuchElementException("Cambio no encontrado"));
        CambioMetadato r = switch (accion) {
            case "aplicar" -> completado.aplicar(c, quien());
            case "rechazar" -> completado.rechazar(c, quien());
            case "revertir" -> completado.revertir(c, quien());
            default -> throw new IllegalArgumentException("Acción no válida");
        };
        return Map.of("id", r.getId(), "estado", r.getEstado(), "detalle", Objects.toString(r.getDetalle(), ""));
    }

    @GetMapping("/cambios/{id}/imagen")
    public ResponseEntity<byte[]> imagen(@PathVariable Long id) throws Exception {
        CambioMetadato c = cambios.findById(id).orElseThrow(() -> new NoSuchElementException("Cambio no encontrado"));
        Path f = completado.archivoPropuesta(c);
        if (f == null || !Files.isRegularFile(f)) throw new NoSuchElementException("Sin imagen");
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).cacheControl(CacheControl.noStore()).body(Files.readAllBytes(f));
    }

    // ── Etiquetas propuestas por la IA ──

    @GetMapping("/etiquetas")
    public Map<String, Object> etiquetasIa(@RequestParam(defaultValue = "0") int pagina) {
        Page<EtiquetaCancion> p = etiquetas.findByOrigenAndEstadoOrderByFechaDesc("ia", EtiquetaCancion.Estado.PROPUESTA, PageRequest.of(Math.max(0, pagina), 40));
        List<Map<String, Object>> filas = new ArrayList<>();
        for (EtiquetaCancion e : p.getContent())
            filas.add(Map.of("id", e.getId(), "tipo", e.getTipo(), "valor", e.getValor(), "modelo", Objects.toString(e.getModelo(), ""),
                    "cancion", biblioteca.porId(e.getCancionId()).map(x -> (Object) x).orElse(Map.of())));
        return Map.of("contenido", filas, "total", p.getTotalElements(), "paginas", p.getTotalPages());
    }

    @PostMapping("/etiquetas/{id}/{accion}")
    public Map<String, Object> etiqueta(@PathVariable Long id, @PathVariable String accion) {
        EtiquetaCancion e = etiquetas.findById(id).orElseThrow(() -> new NoSuchElementException("Etiqueta no encontrada"));
        e.setEstado(switch (accion) {
            case "aceptar" -> EtiquetaCancion.Estado.ACEPTADA;
            case "rechazar" -> EtiquetaCancion.Estado.RECHAZADA;
            default -> throw new IllegalArgumentException("Acción no válida");
        });
        etiquetas.save(e);
        return Map.of("id", id, "estado", e.getEstado());
    }

    // ── Vínculos con YouTube Music que necesitan revisión ──

    @GetMapping("/vinculos")
    public List<Map<String, Object>> vinculosAmbiguos() {
        List<Map<String, Object>> r = new ArrayList<>();
        for (CancionExterna v : vinculos.findTop100ByProveedorAndEstadoOrderByFechaDesc("ytmusic", CancionExterna.Estado.AMBIGUA))
            r.add(Map.of("id", v.getId(), "detalle", Objects.toString(v.getDetalle(), ""), "videoId", Objects.toString(v.getIdExterno(), ""),
                    "cancion", biblioteca.porId(v.getCancionId()).map(x -> (Object) x).orElse(Map.of())));
        return r;
    }

    @PostMapping("/vinculos/{id}/{accion}")
    public Map<String, Object> vinculo(@PathVariable Long id, @PathVariable String accion) {
        CancionExterna v = vinculos.findById(id).orElseThrow(() -> new NoSuchElementException("Vínculo no encontrado"));
        if (v.getEstado() != CancionExterna.Estado.AMBIGUA) throw new IllegalArgumentException("Ese vínculo ya está resuelto");
        switch (accion) {
            case "confirmar" -> { v.setEstado(CancionExterna.Estado.VERIFICADA); v.setMetodo("manual"); }
            case "descartar" -> v.setEstado(CancionExterna.Estado.DESCARTADA);
            default -> throw new IllegalArgumentException("Acción no válida");
        }
        vinculos.save(v);
        senales.recargar();
        return Map.of("id", id, "estado", v.getEstado());
    }
}
