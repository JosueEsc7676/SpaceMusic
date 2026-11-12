package com.bpm.music.experiencias;

import com.bpm.music.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** Rutas musicales, descubrimiento con control, "El punto donde coincidimos", dedicatorias y preparar sin internet. */
@RestController
@RequestMapping("/api")
public class ExperienciasController {

    private final RutasService rutas;
    private final DescubrirService descubrir;
    private final CoincidimosService coincidimos;
    private final DedicatoriasService dedicatorias;
    private final PrepararOfflineService preparar;

    public ExperienciasController(RutasService rutas, DescubrirService descubrir, CoincidimosService coincidimos,
                                  DedicatoriasService dedicatorias, PrepararOfflineService preparar) {
        this.rutas = rutas;
        this.descubrir = descubrir;
        this.coincidimos = coincidimos;
        this.dedicatorias = dedicatorias;
        this.preparar = preparar;
    }

    private static int entero(Object o, int def) {
        try { return o == null ? def : (int) Math.round(Double.parseDouble(String.valueOf(o))); } catch (Exception e) { return def; }
    }

    private static Long id(Object o) {
        try { return o == null || String.valueOf(o).isBlank() ? null : Long.valueOf(String.valueOf(o)); } catch (Exception e) { throw new IllegalArgumentException("Identificador no válido"); }
    }

    private static List<Long> ids(Object o) {
        if (!(o instanceof List<?> l)) return List.of();
        if (l.size() > 200) throw new IllegalArgumentException("Demasiadas canciones");
        return l.stream().map(ExperienciasController::id).filter(Objects::nonNull).toList();
    }

    // ── Rutas musicales ──

    @PostMapping("/rutas")
    public Map<String, Object> ruta(@RequestBody Map<String, Object> b) {
        String desde = Objects.toString(b.get("desde"), "media"), hasta = Objects.toString(b.get("hasta"), "energia");
        if (!RutasService.NIVELES.containsKey(desde) || !RutasService.NIVELES.containsKey(hasta)) throw new IllegalArgumentException("Elige cómo empieza y adónde quieres llegar");
        String ajuste = Objects.toString(b.get("ajuste"), "");
        if (!ajuste.isEmpty() && !Set.of("calma", "energia", "sorpresa", "mantener").contains(ajuste)) throw new IllegalArgumentException("Ajuste no válido");
        double sorpresa = b.get("sorpresa") instanceof Number n ? n.doubleValue() : 0.2;
        return rutas.armar(CurrentUser.id(), new RutasService.Pedido(entero(b.get("minutos"), 25), desde, hasta, id(b.get("inicioId")), id(b.get("finId")), sorpresa),
                ids(b.get("conservar")), ajuste.isEmpty() ? null : ajuste);
    }

    // ── Descubrimiento con control ──

    @GetMapping("/descubrir")
    public List<Map<String, Object>> descubrir(@RequestParam(defaultValue = "cercano") String modo, @RequestParam(defaultValue = "30") int cantidad) {
        if (!Set.of("familiar", "cercano", "sorpresa").contains(modo)) throw new IllegalArgumentException("Modo no válido");
        return descubrir.descubrir(CurrentUser.id(), modo, cantidad);
    }

    @PostMapping("/descubrir/descartar")
    public ResponseEntity<Void> descartar(@RequestBody Map<String, Object> b) {
        descubrir.descartar(CurrentUser.id(), id(b.get("cancionId")), Objects.toString(b.get("tipo"), ""));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/descubrir/estilos-descartados")
    public List<Map<String, Object>> estilosDescartados() {
        return descubrir.estilosDescartados(CurrentUser.id());
    }

    @DeleteMapping("/descubrir/descartes/{id}")
    public ResponseEntity<Void> quitarDescarte(@PathVariable Long id) {
        descubrir.quitarDescarte(CurrentUser.id(), id);
        return ResponseEntity.noContent().build();
    }

    // ── El punto donde coincidimos ──

    @GetMapping("/coincidimos")
    public List<Map<String, Object>> sesiones() { return coincidimos.mias(CurrentUser.id()); }

    @PostMapping("/coincidimos")
    public Map<String, Object> invitar(@RequestBody Map<String, String> b) {
        return Map.of("id", coincidimos.invitar(CurrentUser.id(), b.get("username")).getId());
    }

    @GetMapping("/coincidimos/{id}")
    public Map<String, Object> sesion(@PathVariable Long id) { return coincidimos.ver(id, CurrentUser.id()); }

    @PostMapping("/coincidimos/{id}/respuesta")
    public Map<String, Object> responder(@PathVariable Long id, @RequestBody Map<String, Object> b) {
        return Map.of("estado", coincidimos.responder(id, CurrentUser.id(), Boolean.TRUE.equals(b.get("acepta"))).getEstado());
    }

    @PostMapping("/coincidimos/{id}/cerrar")
    public ResponseEntity<Void> cerrarSesion(@PathVariable Long id) { coincidimos.cerrar(id, CurrentUser.id()); return ResponseEntity.noContent().build(); }

    @PostMapping("/coincidimos/{id}/aportes")
    public ResponseEntity<Void> aportar(@PathVariable Long id, @RequestBody Map<String, Object> b) {
        coincidimos.aportar(id, CurrentUser.id(), id(b.get("cancionId")));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/coincidimos/{id}/aportes/{cancionId}")
    public ResponseEntity<Void> quitarAporte(@PathVariable Long id, @PathVariable Long cancionId) {
        coincidimos.quitarAporte(id, CurrentUser.id(), cancionId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/coincidimos/{id}/reacciones")
    public ResponseEntity<Void> reaccionar(@PathVariable Long id, @RequestBody Map<String, Object> b) {
        coincidimos.reaccionar(id, CurrentUser.id(), id(b.get("cancionId")), Objects.toString(b.get("reaccion"), ""));
        return ResponseEntity.noContent().build();
    }

    // ── Dedicatorias ──

    @GetMapping("/dedicatorias")
    public Map<String, Object> dedicatorias() { return dedicatorias.listas(CurrentUser.id()); }

    @PostMapping("/dedicatorias")
    public Map<String, Object> dedicar(@RequestBody Map<String, Object> b) {
        List<String> lineas = b.get("lineas") instanceof List<?> l ? l.stream().map(x -> Objects.toString(x, "")).limit(40).toList() : null;
        Integer fin = b.get("segundoFin") == null ? null : entero(b.get("segundoFin"), 0);
        Dedicatoria d = dedicatorias.enviar(CurrentUser.id(), Objects.toString(b.get("username"), null), id(b.get("cancionId")),
                entero(b.get("segundo"), 0), Objects.toString(b.get("mensaje"), null), Boolean.TRUE.equals(b.get("completa")), lineas, fin);
        return Map.of("id", d.getId());
    }

    @GetMapping("/dedicatorias/personas")
    public List<Map<String, Object>> personasParaDedicar(@RequestParam(required = false) String q) {
        return dedicatorias.personas(CurrentUser.id(), q);
    }

    @GetMapping("/dedicatorias/{id}")
    public Map<String, Object> dedicatoria(@PathVariable Long id) { return dedicatorias.ver(id, CurrentUser.id()); }

    @PostMapping("/dedicatorias/{id}/respuesta")
    public Map<String, Object> responderDedicatoria(@PathVariable Long id, @RequestBody Map<String, String> b) {
        return dedicatorias.responder(id, CurrentUser.id(), b.get("texto"));
    }

    @PostMapping("/dedicatorias/{id}/guardar")
    public Map<String, Object> guardarDedicatoria(@PathVariable Long id, @RequestBody Map<String, Object> b) {
        return dedicatorias.guardar(id, CurrentUser.id(), !Boolean.FALSE.equals(b.get("guardar")));
    }

    @DeleteMapping("/dedicatorias/{id}")
    public ResponseEntity<Void> ocultarDedicatoria(@PathVariable Long id) { dedicatorias.ocultar(id, CurrentUser.id()); return ResponseEntity.noContent().build(); }

    @PostMapping("/dedicatorias/bloqueos")
    public ResponseEntity<Void> bloquear(@RequestBody Map<String, Object> b) {
        dedicatorias.bloquear(CurrentUser.id(), Objects.toString(b.get("username"), null), !Boolean.FALSE.equals(b.get("bloquear")));
        return ResponseEntity.noContent().build();
    }

    // ── Preparar escucha sin internet ──

    @PostMapping("/sin-internet/preparar")
    public Map<String, Object> prepararOffline(@RequestBody Map<String, Object> b) {
        return preparar.proponer(CurrentUser.id(), entero(b.get("minutos"), 120), entero(b.get("maxMb"), 1000), entero(b.get("favoritas"), 70));
    }

    @PostMapping("/sin-internet/sustituir")
    public Map<String, Object> sustituir(@RequestBody Map<String, Object> b) {
        String tipo = "favorita".equals(b.get("tipo")) ? "favorita" : "descubrimiento";
        long max = b.get("maxBytes") instanceof Number n ? n.longValue() : 0;
        return preparar.sustituto(CurrentUser.id(), tipo, new HashSet<>(ids(b.get("excluir"))), max);
    }
}
