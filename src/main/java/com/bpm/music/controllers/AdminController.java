package com.bpm.music.controllers;

import com.bpm.music.models.Usuario;
import com.bpm.music.repositories.UsuarioRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin")
@CrossOrigin(origins = "*")
public class AdminController {

    @Autowired
    private UsuarioRepository usuarioRepo;

    // ── Lista todos los usuarios ──
    @GetMapping("/usuarios")
    public ResponseEntity<List<Map<String, Object>>> listarUsuarios() {
        return ResponseEntity.ok(
                usuarioRepo.findAll().stream()
                        .map(this::toMap)
                        .collect(Collectors.toList())
        );
    }

    // ── Solicitudes pendientes de colaborador ──
    @GetMapping("/solicitudes")
    public ResponseEntity<List<Map<String, Object>>> getSolicitudes() {
        return ResponseEntity.ok(
                usuarioRepo.findBySolicitudColaborador(Usuario.EstadoSolicitud.PENDIENTE)
                        .stream()
                        .map(this::toMap)
                        .collect(Collectors.toList())
        );
    }

    // ── Aprobar solicitud ──
    @PostMapping("/solicitudes/{userId}/aprobar")
    public ResponseEntity<Map<String, Object>> aprobar(
            @PathVariable Long userId,
            @RequestBody(required = false) Map<String, String> body) {

        Usuario u = usuarioRepo.findById(userId).orElseThrow();
        u.setRol(Usuario.Rol.COLABORADOR);
        u.setSolicitudColaborador(Usuario.EstadoSolicitud.APROBADA);
        u.setRespuestaAdmin(body != null ? body.getOrDefault("nota", "") : "");
        usuarioRepo.save(u);
        return ResponseEntity.ok(Map.of("ok", true, "message", "Colaborador aprobado"));
    }

    // ── Rechazar solicitud ──
    @PostMapping("/solicitudes/{userId}/rechazar")
    public ResponseEntity<Map<String, Object>> rechazar(
            @PathVariable Long userId,
            @RequestBody(required = false) Map<String, String> body) {

        Usuario u = usuarioRepo.findById(userId).orElseThrow();
        u.setSolicitudColaborador(Usuario.EstadoSolicitud.RECHAZADA);
        u.setRespuestaAdmin(body != null ? body.getOrDefault("nota", "") : "");
        usuarioRepo.save(u);
        return ResponseEntity.ok(Map.of("ok", true, "message", "Solicitud rechazada"));
    }

    // ── Cambiar rol manualmente ──
    @PutMapping("/usuarios/{userId}/rol")
    public ResponseEntity<Map<String, Object>> cambiarRol(
            @PathVariable Long userId,
            @RequestBody Map<String, String> body) {

        Usuario u = usuarioRepo.findById(userId).orElseThrow();
        try {
            u.setRol(Usuario.Rol.valueOf(body.get("rol")));
            usuarioRepo.save(u);
            return ResponseEntity.ok(Map.of("ok", true, "rol", u.getRol().name()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Rol inválido"));
        }
    }

    // ── Activar / desactivar usuario ──
    @PutMapping("/usuarios/{userId}/estado")
    public ResponseEntity<Map<String, Object>> cambiarEstado(
            @PathVariable Long userId,
            @RequestBody Map<String, Boolean> body) {

        Usuario u = usuarioRepo.findById(userId).orElseThrow();
        u.setActivo(body.getOrDefault("activo", true));
        usuarioRepo.save(u);
        return ResponseEntity.ok(Map.of("ok", true, "activo", u.isActivo()));
    }

    // ── Resumen del panel admin ──
    @GetMapping("/resumen")
    public ResponseEntity<Map<String, Object>> resumen() {
        List<Usuario> todos = usuarioRepo.findAll();
        long admins       = todos.stream().filter(u -> u.getRol() == Usuario.Rol.ADMIN).count();
        long colaboradores= todos.stream().filter(u -> u.getRol() == Usuario.Rol.COLABORADOR).count();
        long users        = todos.stream().filter(u -> u.getRol() == Usuario.Rol.USER).count();
        long pendientes   = todos.stream()
                .filter(u -> u.getSolicitudColaborador() != null && u.getSolicitudColaborador() == Usuario.EstadoSolicitud.PENDIENTE).count();

        return ResponseEntity.ok(Map.of(
                "totalUsuarios",    todos.size(),
                "admins",           admins,
                "colaboradores",    colaboradores,
                "users",            users,
                "solicitudesPendientes", pendientes
        ));
    }

    private Map<String, Object> toMap(Usuario u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",           u.getId());
        m.put("username",     u.getUsername());
        m.put("email",        u.getEmail());
        m.put("nombreDisplay",u.getNombreDisplay());
        m.put("fotoPerfil",   u.getFotoPerfil());
        m.put("rol",          u.getRol().name());
        m.put("activo",       u.isActivo());
        m.put("emailVerificado", u.isEmailVerificado());
        m.put("solicitudColaborador", u.getSolicitudColaborador() != null ? u.getSolicitudColaborador().name() : "NINGUNA");
        m.put("motivoSolicitud",      u.getMotivoSolicitud());
        m.put("fechaSolicitud",       u.getFechaSolicitud());
        m.put("respuestaAdmin",       u.getRespuestaAdmin());
        m.put("fechaRegistro",        u.getFechaRegistro());
        m.put("ultimoLogin",          u.getUltimoLogin());
        return m;
    }
}