package com.bpm.music.controllers;

import com.bpm.music.security.CurrentUser;
import com.bpm.music.services.AuthService;
import com.bpm.music.services.ImagenService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;
    private final ImagenService imagenes;

    public AuthController(AuthService auth, ImagenService imagenes) {
        this.auth = auth;
        this.imagenes = imagenes;
    }

    /** Indica si falta crear el primer administrador. */
    @GetMapping("/estado")
    public Map<String, Object> estado() {
        return auth.estado();
    }

    @PostMapping("/setup-admin")
    public ResponseEntity<Map<String, Object>> setupAdmin(@RequestBody Map<String, String> b, HttpServletRequest req) {
        String ip = req.getRemoteAddr();
        boolean local = "127.0.0.1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip);
        return respuesta(auth.crearPrimerAdmin(b.get("email"), b.get("username"), b.get("password"),
                b.get("nombreDisplay"), b.get("setupKey"), local));
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody Map<String, String> b) {
        return respuesta(auth.registrar(b.get("email"), b.get("username"), b.get("password"), b.get("nombreDisplay")));
    }

    @GetMapping("/verify")
    public ResponseEntity<Void> verify(@RequestParam(required = false) String token) {
        var r = auth.verificarEmail(token);
        return redirigir(r.containsKey("error") ? "/login.html?verified=false" : "/login.html?verified=true");
    }

    @PostMapping("/resend-verification")
    public Map<String, Object> resend(@RequestBody Map<String, String> b) {
        return auth.reenviarVerificacion(b.get("email"));
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> b, HttpServletRequest req) {
        String usuario = b.getOrDefault("usuario", b.get("email"));
        return respuesta(auth.login(usuario, b.get("password"), req.getRemoteAddr()));
    }

    @PostMapping("/forgot-password")
    public Map<String, Object> forgot(@RequestBody Map<String, String> b) {
        return auth.solicitarReset(b.get("email"));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, Object>> reset(@RequestBody Map<String, String> b) {
        return respuesta(auth.resetPassword(b.get("token"), b.get("password")));
    }

    @GetMapping("/confirm-email")
    public ResponseEntity<Void> confirmEmail(@RequestParam(required = false) String token) {
        var r = auth.confirmarCambioEmail(token);
        return redirigir(r.containsKey("error") ? "/login.html?emailChanged=false" : "/login.html?emailChanged=true");
    }

    // ── Sesión actual ──

    @GetMapping("/me")
    public Map<String, Object> me() {
        return auth.perfil(CurrentUser.id());
    }

    @PutMapping("/me")
    public Map<String, Object> actualizar(@RequestBody Map<String, String> b) {
        return auth.actualizarPerfil(CurrentUser.id(), b.get("nombreDisplay"));
    }

    @PostMapping("/refresh")
    public ResponseEntity<Map<String, Object>> refresh() {
        var r = auth.refrescar(CurrentUser.id());
        return r.containsKey("error") ? ResponseEntity.status(HttpStatus.FORBIDDEN).body(r) : ResponseEntity.ok(r);
    }

    @PostMapping("/me/foto")
    public Map<String, Object> foto(@RequestParam("file") MultipartFile file) throws IOException {
        Long id = CurrentUser.id();
        String nombre = imagenes.guardar(file, "avatars", "avatar_" + id, 400);
        String url = "/api/usuario/avatar/" + nombre;
        auth.guardarFoto(id, url);
        return Map.of("ok", true, "fotoPerfil", url);
    }

    /** Alias antiguo de /api/usuario/avatar/{archivo}. */
    @GetMapping("/avatar/{archivo}")
    public ResponseEntity<Void> avatar(@PathVariable String archivo) {
        return redirigir("/api/usuario/avatar/" + archivo.replaceAll("[^A-Za-z0-9._-]", ""));
    }

    private static ResponseEntity<Map<String, Object>> respuesta(Map<String, Object> r) {
        return r.containsKey("error") ? ResponseEntity.badRequest().body(r) : ResponseEntity.ok(r);
    }

    private static ResponseEntity<Void> redirigir(String url) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).build();
    }
}
