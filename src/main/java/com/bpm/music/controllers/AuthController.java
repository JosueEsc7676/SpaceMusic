package com.bpm.music.controllers;

import com.bpm.music.services.AuthService;
import com.bpm.music.services.UsuarioService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*")
public class AuthController {

    @Autowired private AuthService authService;
    @Autowired private UsuarioService usuarioService;

    // ── Crear primer admin (solo funciona si no existe ningún admin) ──
    @PostMapping("/setup-admin")
    public ResponseEntity<Map<String, Object>> setupAdmin(@RequestBody Map<String, String> body) {
        var result = authService.crearPrimerAdmin(
                body.get("email"),
                body.get("username"),
                body.get("password"),
                body.get("nombreDisplay"),
                body.get("setupKey")
        );
        if (result.containsKey("error")) return ResponseEntity.badRequest().body(result);
        return ResponseEntity.ok(result);
    }

    // ── Registro ──
    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody Map<String, String> body) {
        var result = authService.registrar(
                body.get("email"),
                body.get("username"),
                body.get("password"),
                body.get("nombreDisplay")
        );
        if (result.containsKey("error")) return ResponseEntity.badRequest().body(result);
        return ResponseEntity.ok(result);
    }

    // ── Verificar email ──
    @GetMapping("/verify")
    public ResponseEntity<?> verify(@RequestParam String token) {
        var result = authService.verificarEmail(token);
        if (result.containsKey("error")) {
            return ResponseEntity.badRequest().body(result);
        }
        // Redirigir al login con mensaje de éxito
        return ResponseEntity.status(302)
                .header("Location", "/login.html?verified=true")
                .build();
    }

    // ── Login ──
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        var result = authService.login(body.get("email"), body.get("password"));
        if (result.containsKey("error")) return ResponseEntity.badRequest().body(result);
        return ResponseEntity.ok(result);
    }

    // ── Solicitar reset de contraseña ──
    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, Object>> forgotPassword(@RequestBody Map<String, String> body) {
        return ResponseEntity.ok(authService.solicitarReset(body.get("email")));
    }

    // ── Resetear contraseña ──
    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, Object>> resetPassword(@RequestBody Map<String, String> body) {
        var result = authService.resetPassword(body.get("token"), body.get("password"));
        if (result.containsKey("error")) return ResponseEntity.badRequest().body(result);
        return ResponseEntity.ok(result);
    }

    // ── Perfil del usuario logueado ──
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        return ResponseEntity.ok(authService.getPerfil(userId));
    }

    // ── Actualizar perfil (nombre y foto) ──
    @PutMapping("/me")
    public ResponseEntity<Map<String, Object>> updateProfile(
            Authentication auth,
            @RequestBody Map<String, String> body) {
        Long userId = (Long) auth.getPrincipal();
        return ResponseEntity.ok(
                authService.actualizarPerfil(userId, body.get("nombreDisplay"), body.get("fotoPerfil"))
        );
    }

    // ── Subir foto de perfil ──
    @PostMapping("/me/foto")
    public ResponseEntity<Map<String, Object>> uploadFoto(
            Authentication auth,
            @RequestParam("file") MultipartFile file) {
        Long userId = (Long) auth.getPrincipal();
        return ResponseEntity.ok(usuarioService.subirFotoPerfil(userId, file));
    }

    // ── Reenviar email de verificación ──
    @PostMapping("/resend-verification")
    public ResponseEntity<Map<String, Object>> resendVerification(@RequestBody Map<String, String> body) {
        return ResponseEntity.ok(usuarioService.reenviarVerificacion(body.get("email")));
    }

    // ── Confirmar cambio de email ──
    @GetMapping("/confirm-email")
    public ResponseEntity<?> confirmEmail(@RequestParam String token) {
        var result = authService.confirmarCambioEmail(token);
        if (result.containsKey("error")) {
            return ResponseEntity.badRequest().body(result);
        }
        return ResponseEntity.status(302)
                .header("Location", "/index.html?emailChanged=true")
                .build();
    }

    // ── Servir avatar (alias de /api/usuario/avatar) ──
    @GetMapping("/avatar/{filename}")
    public ResponseEntity<byte[]> getAvatar(
            @PathVariable String filename,
            @org.springframework.beans.factory.annotation.Value("${music.playlist-images-path:./playlist-images}") String uploadsPath) {
        try {
            java.nio.file.Path ruta = java.nio.file.Paths.get(uploadsPath + "/avatars", filename);
            if (!java.nio.file.Files.exists(ruta)) return ResponseEntity.notFound().build();
            byte[] data = java.nio.file.Files.readAllBytes(ruta);
            String type = java.nio.file.Files.probeContentType(ruta);
            if (type == null) type = "image/jpeg";
            return ResponseEntity.ok()
                    .header("Content-Type", type)
                    .header("Cache-Control", "max-age=86400")
                    .body(data);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }
}