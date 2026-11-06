package com.bpm.music.controllers;

import com.bpm.music.dtos.CancionDTO;
import com.bpm.music.models.Usuario;
import com.bpm.music.repositories.*;
import com.bpm.music.security.CurrentUser;
import com.bpm.music.services.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/** Administración → Soporte técnico y Géneros (solo ADMIN: todo /api/admin/** lo exige SecurityConfig). */
@RestController
@RequestMapping("/api/admin")
public class SoporteController {

    private final UsuarioRepository usuarios;
    private final CancionRepository canciones;
    private final PlaylistRepository playlists;
    private final MensajeRepository mensajes;
    private final NotificacionRepository notifs;
    private final UsuarioReproduccionRepository reproducciones;
    private final UsuarioFavoritoRepository favoritos;
    private final SeguimientoRepository seguimientos;
    private final EventosService eventos;
    private final RegistroErrores errores;
    private final GeneroService generos;
    private final BibliotecaService biblioteca;
    private final EscanerService escaner;
    private final NotificacionesService notificaciones;
    private final AuthService auth;
    private final LocalDateTime inicio = LocalDateTime.now();

    public SoporteController(UsuarioRepository usuarios, CancionRepository canciones, PlaylistRepository playlists,
                             MensajeRepository mensajes, NotificacionRepository notifs, UsuarioReproduccionRepository reproducciones,
                             UsuarioFavoritoRepository favoritos, SeguimientoRepository seguimientos, EventosService eventos,
                             RegistroErrores errores, GeneroService generos, BibliotecaService biblioteca, EscanerService escaner,
                             NotificacionesService notificaciones, AuthService auth) {
        this.usuarios = usuarios;
        this.canciones = canciones;
        this.playlists = playlists;
        this.mensajes = mensajes;
        this.notifs = notifs;
        this.reproducciones = reproducciones;
        this.favoritos = favoritos;
        this.seguimientos = seguimientos;
        this.eventos = eventos;
        this.errores = errores;
        this.generos = generos;
        this.biblioteca = biblioteca;
        this.escaner = escaner;
        this.notificaciones = notificaciones;
        this.auth = auth;
    }

    // ═══════════════════ Estado del sistema ═══════════════════

    @GetMapping("/sistema")
    public Map<String, Object> sistema() {
        Runtime rt = Runtime.getRuntime();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("version", versionApp());
        r.put("inicio", inicio);
        r.put("activoSegundos", Duration.between(inicio, LocalDateTime.now()).getSeconds());
        r.put("java", System.getProperty("java.version"));
        r.put("sistema", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        r.put("procesadores", rt.availableProcessors());
        double carga = ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
        r.put("carga", carga < 0 ? null : Math.round(carga * 100) / 100.0);
        r.put("memoriaUsadaMb", (rt.totalMemory() - rt.freeMemory()) / 1_048_576);
        r.put("memoriaMaxMb", rt.maxMemory() / 1_048_576);
        File musica = new File(String.valueOf(escaner.estado().get("ruta")));
        File disco = musica.exists() ? musica : new File(".");
        r.put("discoLibreGb", Math.round(disco.getUsableSpace() / 1e8) / 10.0);
        r.put("discoTotalGb", Math.round(disco.getTotalSpace() / 1e8) / 10.0);
        r.put("canciones", canciones.count());
        r.put("usuarios", usuarios.count());
        r.put("playlists", playlists.count());
        r.put("mensajes", mensajes.count());
        r.put("notificaciones", notifs.count());
        r.put("reproducciones24h", reproducciones.countByFechaAfter(LocalDateTime.now().minusHours(24)));
        // Quién está conectado ahora (web o app abierta)
        Map<Long, Integer> con = eventos.conectados();
        List<Map<String, Object>> enLinea = new ArrayList<>();
        usuarios.findAllById(con.keySet()).forEach(u -> enLinea.add(Map.of(
                "id", u.getId(), "username", u.getUsername(), "nombre", u.getNombreVisible(), "dispositivos", con.get(u.getId()))));
        r.put("enLinea", enLinea);
        r.put("erroresRecientes", errores.ultimos().stream().filter(e -> "ERROR".equals(e.get("nivel"))).count());
        r.put("escaner", escaner.estado());
        return r;
    }

    @GetMapping("/errores")
    public List<Map<String, Object>> errores() {
        return errores.ultimos();
    }

    @DeleteMapping("/errores")
    public Map<String, Object> vaciarErrores() {
        errores.vaciar();
        return Map.of("ok", true);
    }

    /** Vuelve a cargar la biblioteca en memoria (si algo se ve desactualizado). */
    @PostMapping("/sistema/refrescar")
    public Map<String, Object> refrescar() {
        biblioteca.invalidar();
        return Map.of("ok", true, "canciones", biblioteca.total());
    }

    /** Aviso para todos los usuarios (aparece en sus notificaciones). */
    @PostMapping("/aviso")
    public ResponseEntity<Map<String, Object>> aviso(@RequestBody Map<String, String> b) {
        String texto = Optional.ofNullable(b.get("texto")).orElse("").trim();
        String ruta = Optional.ofNullable(b.get("ruta")).orElse("").trim();
        if (texto.isEmpty() || texto.length() > 300) return ResponseEntity.badRequest().body(Map.of("error", "Escribe un aviso de hasta 300 caracteres"));
        // Solo rutas internas de la app (nada de enlaces externos)
        if (!ruta.isEmpty() && !ruta.matches("#/[\\w\\-/%?=&.]{0,120}")) return ResponseEntity.badRequest().body(Map.of("error", "El enlace debe ser de la app, por ejemplo #/novedades"));
        int n = 0;
        for (Usuario u : usuarios.findAll()) {
            if (!u.isActivo() || u.getEstadoCuenta() != Usuario.EstadoCuenta.APROBADA) continue;
            notificaciones.crear(u.getId(), "AVISO", CurrentUser.id(), texto, ruta.isEmpty() ? null : ruta);
            n++;
        }
        return ResponseEntity.ok(Map.of("ok", true, "enviados", n));
    }

    // ═══════════════════ Soporte a un usuario ═══════════════════

    @GetMapping("/usuarios/{id}/detalle")
    public Map<String, Object> detalle(@PathVariable Long id) {
        Usuario u = usuarios.findById(id).orElseThrow(() -> new NoSuchElementException("Usuario no encontrado"));
        Map<String, Object> r = new LinkedHashMap<>(AuthService.perfilPublico(u));
        r.put("email", u.getEmail());
        r.put("activo", u.isActivo());
        r.put("emailVerificado", u.isEmailVerificado());
        r.put("estadoCuenta", u.getEstadoCuenta() == null ? null : u.getEstadoCuenta().name());
        r.put("fechaRegistro", u.getFechaRegistro());
        r.put("ultimoLogin", u.getUltimoLogin());
        r.put("dispositivosConectados", eventos.conectados().getOrDefault(id, 0));
        r.put("playlists", playlists.findByUsuarioIdOrderByFechaCreacionDesc(id).size());
        r.put("favoritos", favoritos.countByUsuarioId(id));
        r.put("reproducciones", reproducciones.countByUsuarioId(id));
        r.put("seguidores", seguimientos.countBySeguidoId(id));
        r.put("siguiendo", seguimientos.countBySeguidorId(id));
        r.put("mensajesSinLeer", mensajes.countByParaIdAndLeidoFalse(id));
        r.put("notificacionesSinLeer", notifs.countByUsuarioIdAndLeidaFalse(id));
        List<Map<String, Object>> ultimas = new ArrayList<>();
        for (Object[] f : reproducciones.findUltimas(id, PageRequest.of(0, 8))) {
            Optional<CancionDTO> c = biblioteca.porId(((Number) f[0]).longValue());
            c.ifPresent(s -> ultimas.add(Map.of("nombre", s.getNombre(), "artista", s.getArtista(), "fecha", f[1])));
        }
        r.put("ultimasCanciones", ultimas);
        return r;
    }

    /** Envía al usuario el correo para crear una contraseña nueva (el admin nunca ve ni pone contraseñas). */
    @PostMapping("/usuarios/{id}/recuperar")
    public Map<String, Object> recuperar(@PathVariable Long id) {
        Usuario u = usuarios.findById(id).orElseThrow(() -> new NoSuchElementException("Usuario no encontrado"));
        auth.solicitarReset(u.getEmail());
        return Map.of("ok", true);
    }

    /** Cierra la sesión del usuario en todos sus dispositivos (p. ej. si perdió el teléfono). */
    @PostMapping("/usuarios/{id}/cerrar-sesiones")
    public ResponseEntity<Map<String, Object>> cerrarSesiones(@PathVariable Long id) {
        if (Objects.equals(id, CurrentUser.id())) return ResponseEntity.badRequest().body(Map.of("error", "Para tu propia cuenta usa Cambiar contraseña"));
        Usuario u = usuarios.findById(id).orElseThrow(() -> new NoSuchElementException("Usuario no encontrado"));
        u.setSesionesDesde(LocalDateTime.now());
        usuarios.save(u);
        com.bpm.music.security.JwtFilter.olvidar(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ═══════════════════ Géneros ═══════════════════

    @GetMapping("/generos")
    public Map<String, Object> generos() {
        return generos.resumenAdmin();
    }

    @PutMapping("/generos/artista")
    public ResponseEntity<Map<String, Object>> fijarGenero(@RequestBody Map<String, String> b) {
        try {
            return ResponseEntity.ok(Map.of("ok", true, "canciones", generos.fijarGenero(b.get("artista"), b.get("genero"))));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/generos/buscar")
    public Map<String, Object> buscarGeneros() {
        return Map.of("iniciado", generos.buscarEnSegundoPlano());
    }

    // ═══════════════════ Helpers ═══════════════════

    private static String versionApp() {
        try (InputStream in = new ClassPathResource("static/app/novedades.json").getInputStream()) {
            return new ObjectMapper().readTree(in).path("version").asText("");
        } catch (Exception e) {
            return "";
        }
    }
}
