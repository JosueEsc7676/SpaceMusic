package com.bpm.music.controllers;

import com.bpm.music.models.Usuario;
import com.bpm.music.repositories.UsuarioRepository;
import com.bpm.music.security.CurrentUser;
import com.bpm.music.services.AuthService;
import com.bpm.music.services.EscanerService;
import com.bpm.music.services.MailService;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** Panel de administración (solo ADMIN, ver SecurityConfig). */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final UsuarioRepository usuarios;
    private final EscanerService escaner;
    private final MailService mail;

    public AdminController(UsuarioRepository usuarios, EscanerService escaner, MailService mail) {
        this.usuarios = usuarios;
        this.escaner = escaner;
        this.mail = mail;
    }

    // ═══════════════════ Usuarios ═══════════════════

    @GetMapping("/usuarios")
    public List<Map<String, Object>> listar() {
        return usuarios.findAll().stream()
                .sorted(Comparator.comparing(Usuario::getId))
                .map(this::toMap).toList();
    }

    @GetMapping("/solicitudes")
    public List<Map<String, Object>> solicitudes() {
        return usuarios.findBySolicitudColaborador(Usuario.EstadoSolicitud.PENDIENTE).stream().map(this::toMap).toList();
    }

    @PostMapping("/solicitudes/{userId}/aprobar")
    @Transactional
    public Map<String, Object> aprobar(@PathVariable Long userId, @RequestBody(required = false) Map<String, String> b) {
        Usuario u = usuario(userId);
        if (u.getRol() == Usuario.Rol.USER) u.setRol(Usuario.Rol.COLABORADOR);
        u.setSolicitudColaborador(Usuario.EstadoSolicitud.APROBADA);
        u.setRespuestaAdmin(nota(b));
        usuarios.save(u);
        mail.solicitudResuelta(u.getEmail(), u.getNombreVisible(), true, u.getRespuestaAdmin());
        return Map.of("ok", true, "usuario", toMap(u));
    }

    @PostMapping("/solicitudes/{userId}/rechazar")
    @Transactional
    public Map<String, Object> rechazar(@PathVariable Long userId, @RequestBody(required = false) Map<String, String> b) {
        Usuario u = usuario(userId);
        u.setSolicitudColaborador(Usuario.EstadoSolicitud.RECHAZADA);
        u.setRespuestaAdmin(nota(b));
        usuarios.save(u);
        mail.solicitudResuelta(u.getEmail(), u.getNombreVisible(), false, u.getRespuestaAdmin());
        return Map.of("ok", true, "usuario", toMap(u));
    }

    @PutMapping("/usuarios/{userId}/rol")
    @Transactional
    public ResponseEntity<Map<String, Object>> rol(@PathVariable Long userId, @RequestBody Map<String, String> b) {
        Usuario u = usuario(userId);
        Usuario.Rol nuevo;
        try {
            nuevo = Usuario.Rol.valueOf(b.get("rol"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Rol inválido"));
        }
        if (u.getRol() == Usuario.Rol.ADMIN && nuevo != Usuario.Rol.ADMIN && usuarios.countByRol(Usuario.Rol.ADMIN) <= 1) {
            return ResponseEntity.badRequest().body(Map.of("error", "Debe quedar al menos un administrador"));
        }
        u.setRol(nuevo);
        if (nuevo != Usuario.Rol.USER && u.getSolicitudColaborador() == Usuario.EstadoSolicitud.PENDIENTE) {
            u.setSolicitudColaborador(Usuario.EstadoSolicitud.APROBADA);
        }
        usuarios.save(u);
        return ResponseEntity.ok(Map.of("ok", true, "usuario", toMap(u)));
    }

    @PutMapping("/usuarios/{userId}/estado")
    @Transactional
    public ResponseEntity<Map<String, Object>> estado(@PathVariable Long userId, @RequestBody Map<String, Boolean> b) {
        Usuario u = usuario(userId);
        boolean activo = b.getOrDefault("activo", true);
        if (!activo && Objects.equals(u.getId(), CurrentUser.id())) {
            return ResponseEntity.badRequest().body(Map.of("error", "No puedes desactivar tu propia cuenta"));
        }
        u.setActivo(activo);
        usuarios.save(u);
        return ResponseEntity.ok(Map.of("ok", true, "usuario", toMap(u)));
    }

    /** Marca el correo como verificado (útil si el correo no llegó). */
    @PostMapping("/usuarios/{userId}/verificar")
    @Transactional
    public Map<String, Object> verificar(@PathVariable Long userId) {
        Usuario u = usuario(userId);
        u.setEmailVerificado(true);
        u.setVerificationToken(null);
        usuarios.save(u);
        return Map.of("ok", true, "usuario", toMap(u));
    }

    @GetMapping("/resumen")
    public Map<String, Object> resumen() {
        List<Usuario> todos = usuarios.findAll();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("totalUsuarios", todos.size());
        r.put("admins", todos.stream().filter(u -> u.getRol() == Usuario.Rol.ADMIN).count());
        r.put("colaboradores", todos.stream().filter(u -> u.getRol() == Usuario.Rol.COLABORADOR).count());
        r.put("users", todos.stream().filter(u -> u.getRol() == Usuario.Rol.USER).count());
        r.put("inactivos", todos.stream().filter(u -> !u.isActivo()).count());
        r.put("solicitudesPendientes", todos.stream()
                .filter(u -> u.getSolicitudColaborador() == Usuario.EstadoSolicitud.PENDIENTE).count());
        r.put("correoConfigurado", mail.configurado());
        return r;
    }

    // ═══════════════════ Biblioteca ═══════════════════

    @GetMapping("/biblioteca")
    public Map<String, Object> biblioteca() {
        return escaner.estado();
    }

    @PostMapping("/biblioteca/escanear")
    public Map<String, Object> escanear() {
        boolean iniciado = escaner.escanearEnSegundoPlano();
        Map<String, Object> r = new LinkedHashMap<>(escaner.estado());
        r.put("iniciado", iniciado);
        return r;
    }

    @PostMapping("/biblioteca/metadatos")
    public Map<String, Object> metadatos(@RequestParam(defaultValue = "false") boolean portadas) {
        boolean iniciado = escaner.refrescarMetadatosEnSegundoPlano(portadas);
        Map<String, Object> r = new LinkedHashMap<>(escaner.estado());
        r.put("iniciado", iniciado);
        return r;
    }

    @PostMapping("/biblioteca/limpiar")
    public Map<String, Object> limpiar(@RequestParam(defaultValue = "false") boolean forzar) {
        return escaner.limpiarHuerfanas(forzar);
    }

    // ═══════════════════ Helpers ═══════════════════

    private Usuario usuario(Long id) {
        return usuarios.findById(id).orElseThrow(() -> new NoSuchElementException("Usuario no encontrado"));
    }

    private static String nota(Map<String, String> b) {
        if (b == null) return "";
        String n = b.getOrDefault("nota", "").trim();
        return n.length() > 200 ? n.substring(0, 200) : n;
    }

    private Map<String, Object> toMap(Usuario u) {
        Map<String, Object> m = new LinkedHashMap<>(AuthService.perfilPublico(u));
        m.put("activo", u.isActivo());
        m.put("emailVerificado", u.isEmailVerificado());
        m.put("motivoSolicitud", u.getMotivoSolicitud());
        m.put("fechaSolicitud", u.getFechaSolicitud());
        return m;
    }
}
