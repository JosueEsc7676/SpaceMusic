package com.bpm.music.services;

import com.bpm.music.models.Usuario;
import com.bpm.music.repositories.UsuarioRepository;
import com.bpm.music.security.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class AuthService {

    @Autowired private UsuarioRepository repo;
    @Autowired private PasswordEncoder encoder;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private JavaMailSender mailSender;

    @Value("${app.base-url:http://localhost:8080}")
    private String appBaseUrl;

    @Value("${app.setup-key:spacemusic-setup-2024}")
    private String setupKey;

    @Value("${spring.mail.username}")
    private String fromEmail;

    // =============================================
    // REGISTRO
    // =============================================
    @Transactional
    public Map<String, Object> registrar(String email, String username,
                                         String password, String nombreDisplay) {
        Map<String, Object> result = new HashMap<>();

        if (repo.existsByEmail(email)) {
            result.put("error", "El correo ya está registrado");
            return result;
        }
        if (repo.existsByUsername(username)) {
            result.put("error", "Ese nombre de usuario ya está en uso");
            return result;
        }

        String verToken = UUID.randomUUID().toString();

        Usuario usuario = Usuario.builder()
                .email(email.toLowerCase().trim())
                .username(username.trim())
                .password(encoder.encode(password))
                .nombreDisplay(nombreDisplay != null ? nombreDisplay : username)
                .rol(Usuario.Rol.USER)
                .verificationToken(verToken)
                .build();

        repo.save(usuario);

        // Enviar email de verificación
        _enviarVerificacion(email, username, verToken);

        result.put("ok", true);
        result.put("message", "Cuenta creada. Revisa tu correo para verificar tu cuenta.");
        return result;
    }

    // =============================================
    // VERIFICAR EMAIL
    // =============================================
    @Transactional
    public Map<String, Object> verificarEmail(String token) {
        Map<String, Object> result = new HashMap<>();
        Optional<Usuario> opt = repo.findByVerificationToken(token);

        if (opt.isEmpty()) {
            result.put("error", "Token inválido o ya usado");
            return result;
        }

        Usuario u = opt.get();
        u.setEmailVerificado(true);
        u.setVerificationToken(null);
        repo.save(u);

        result.put("ok", true);
        result.put("message", "Email verificado. Ya puedes iniciar sesión.");
        return result;
    }

    // =============================================
    // LOGIN
    // =============================================
    @Transactional
    public Map<String, Object> login(String emailOrUsername, String password) {
        Map<String, Object> result = new HashMap<>();

        // Buscar por email o username
        Optional<Usuario> opt = emailOrUsername.contains("@")
                ? repo.findByEmail(emailOrUsername.toLowerCase().trim())
                : repo.findByUsername(emailOrUsername.trim());

        if (opt.isEmpty()) {
            result.put("error", "Usuario o contraseña incorrectos");
            return result;
        }

        Usuario u = opt.get();

        if (!u.isActivo()) {
            result.put("error", "Cuenta desactivada. Contacta al administrador.");
            return result;
        }

        if (!encoder.matches(password, u.getPassword())) {
            result.put("error", "Usuario o contraseña incorrectos");
            return result;
        }

        if (!u.isEmailVerificado()) {
            result.put("error", "Debes verificar tu correo antes de entrar.");
            result.put("needsVerification", true);
            return result;
        }

        // Actualizar último login
        u.setUltimoLogin(LocalDateTime.now());
        repo.save(u);

        String token = jwtUtil.generateToken(u.getId(), u.getUsername(), u.getRol().name());

        result.put("ok", true);
        result.put("token", token);
        result.put("usuario", _toPublicMap(u));
        return result;
    }

    // =============================================
    // SOLICITAR RECUPERACIÓN DE CONTRASEÑA
    // =============================================
    @Transactional
    public Map<String, Object> solicitarReset(String email) {
        Map<String, Object> result = new HashMap<>();

        Optional<Usuario> opt = repo.findByEmail(email.toLowerCase().trim());
        // Responder igual aunque no exista (seguridad)
        result.put("ok", true);
        result.put("message", "Si el correo existe, recibirás un enlace de recuperación.");

        if (opt.isEmpty()) return result;

        Usuario u = opt.get();
        String resetToken = UUID.randomUUID().toString();

        u.setResetToken(resetToken);
        u.setResetTokenExpiry(LocalDateTime.now().plusHours(2)); // válido 2 horas
        repo.save(u);

        _enviarReset(email, u.getUsername(), resetToken);
        return result;
    }

    // =============================================
    // RESETEAR CONTRASEÑA
    // =============================================
    @Transactional
    public Map<String, Object> resetPassword(String token, String newPassword) {
        Map<String, Object> result = new HashMap<>();

        Optional<Usuario> opt = repo.findByResetToken(token);
        if (opt.isEmpty()) {
            result.put("error", "Token inválido o ya usado");
            return result;
        }

        Usuario u = opt.get();

        if (u.getResetTokenExpiry() == null ||
                LocalDateTime.now().isAfter(u.getResetTokenExpiry())) {
            result.put("error", "El enlace expiró. Solicita uno nuevo.");
            return result;
        }

        if (newPassword == null || newPassword.length() < 8) {
            result.put("error", "La contraseña debe tener al menos 8 caracteres");
            return result;
        }

        u.setPassword(encoder.encode(newPassword));
        u.setResetToken(null);
        u.setResetTokenExpiry(null);
        repo.save(u);

        result.put("ok", true);
        result.put("message", "Contraseña actualizada correctamente.");
        return result;
    }

    // =============================================
    // ACTUALIZAR PERFIL (foto y nombre display)
    // =============================================
    @Transactional
    public Map<String, Object> actualizarPerfil(Long userId, String nombreDisplay, String fotoPerfil) {
        Usuario u = repo.findById(userId).orElseThrow();
        if (nombreDisplay != null && !nombreDisplay.isBlank()) u.setNombreDisplay(nombreDisplay);
        if (fotoPerfil    != null && !fotoPerfil.isBlank())    u.setFotoPerfil(fotoPerfil);
        repo.save(u);
        return Map.of("ok", true, "usuario", _toPublicMap(u));
    }

    // =============================================
    // OBTENER PERFIL
    // =============================================
    public Map<String, Object> getPerfil(Long userId) {
        Usuario u = repo.findById(userId).orElseThrow();
        return _toPublicMap(u);
    }

    // =============================================
    // HELPERS
    // =============================================
    private Map<String, Object> _toPublicMap(Usuario u) {
        Map<String, Object> m = new HashMap<>();
        m.put("id",            u.getId());
        m.put("username",      u.getUsername());
        m.put("email",         u.getEmail());
        m.put("nombreDisplay", u.getNombreDisplay());
        m.put("fotoPerfil",    u.getFotoPerfil());
        m.put("rol",           u.getRol().name());
        m.put("fechaRegistro", u.getFechaRegistro());
        m.put("ultimoLogin",   u.getUltimoLogin());
        m.put("solicitudColaborador", u.getSolicitudColaborador().name());
        return m;
    }

    // =============================================
    // CREAR PRIMER ADMIN — solo funciona si NO existe ningún admin
    // =============================================
    @Transactional
    public Map<String, Object> crearPrimerAdmin(String email, String username,
                                                String password, String nombreDisplay,
                                                String setupKey) {
        Map<String, Object> result = new HashMap<>();

        // Verificar clave de setup (definida en application.properties)
        if (!setupKey.equals(this.setupKey)) {
            result.put("error", "Clave de setup incorrecta");
            return result;
        }

        // Solo permitir si NO existe ningún admin
        boolean adminExiste = repo.findAll().stream()
                .anyMatch(u -> u.getRol() == com.bpm.music.models.Usuario.Rol.ADMIN);
        if (adminExiste) {
            result.put("error", "Ya existe un administrador. Este endpoint está deshabilitado.");
            return result;
        }

        if (repo.existsByEmail(email)) {
            result.put("error", "El correo ya está registrado");
            return result;
        }
        if (repo.existsByUsername(username)) {
            result.put("error", "El nombre de usuario ya está en uso");
            return result;
        }
        if (password == null || password.length() < 8) {
            result.put("error", "La contraseña debe tener al menos 8 caracteres");
            return result;
        }

        Usuario admin = Usuario.builder()
                .email(email.toLowerCase().trim())
                .username(username.trim())
                .password(encoder.encode(password))
                .nombreDisplay(nombreDisplay != null ? nombreDisplay : username)
                .rol(com.bpm.music.models.Usuario.Rol.ADMIN)
                .activo(true)
                .emailVerificado(true)  // admin no necesita verificar email
                .build();

        repo.save(admin);

        String token = jwtUtil.generateToken(admin.getId(), admin.getUsername(), admin.getRol().name());
        result.put("ok", true);
        result.put("token", token);
        result.put("usuario", _toPublicMap(admin));
        result.put("message", "✅ Admin creado. Guarda bien estas credenciales.");
        return result;
    }

    // =============================================
    // CAMBIAR EMAIL
    // =============================================
    @Transactional
    public Map<String, Object> cambiarEmail(Long userId, String nuevoEmail, String password) {
        Map<String, Object> result = new HashMap<>();
        Usuario u = repo.findById(userId).orElseThrow();

        if (!encoder.matches(password, u.getPassword())) {
            result.put("error", "Contraseña incorrecta"); return result;
        }
        if (repo.existsByEmail(nuevoEmail.toLowerCase().trim())) {
            result.put("error", "Ese correo ya está en uso"); return result;
        }

        String verToken = java.util.UUID.randomUUID().toString();
        u.setVerificationToken(verToken);
        // Guardar nuevo email temporalmente en resetToken field hasta verificar
        u.setResetToken("email:" + nuevoEmail.toLowerCase().trim());
        u.setResetTokenExpiry(java.time.LocalDateTime.now().plusHours(24));
        repo.save(u);

        _enviarVerificacionNuevoEmail(nuevoEmail, u.getUsername(), verToken);
        result.put("ok", true);
        result.put("message", "Se envió un enlace de confirmación a " + nuevoEmail);
        return result;
    }

    @Transactional
    public Map<String, Object> confirmarCambioEmail(String token) {
        Map<String, Object> result = new HashMap<>();
        var opt = repo.findByVerificationToken(token);
        if (opt.isEmpty()) { result.put("error", "Token inválido"); return result; }

        Usuario u = opt.get();
        if (u.getResetToken() == null || !u.getResetToken().startsWith("email:")) {
            result.put("error", "Token inválido"); return result;
        }
        if (u.getResetTokenExpiry() != null &&
                java.time.LocalDateTime.now().isAfter(u.getResetTokenExpiry())) {
            result.put("error", "Token expirado"); return result;
        }

        String nuevoEmail = u.getResetToken().substring(6);
        u.setEmail(nuevoEmail);
        u.setVerificationToken(null);
        u.setResetToken(null);
        u.setResetTokenExpiry(null);
        repo.save(u);

        result.put("ok", true);
        result.put("message", "Email actualizado correctamente");
        return result;
    }

    // =============================================
    // CAMBIAR CONTRASEÑA (desde perfil, con contraseña actual)
    // =============================================
    @Transactional
    public Map<String, Object> cambiarPassword(Long userId, String actual, String nueva) {
        Map<String, Object> result = new HashMap<>();
        if (nueva == null || nueva.length() < 8) {
            result.put("error", "La nueva contraseña debe tener al menos 8 caracteres"); return result;
        }
        Usuario u = repo.findById(userId).orElseThrow();
        if (!encoder.matches(actual, u.getPassword())) {
            result.put("error", "La contraseña actual es incorrecta"); return result;
        }
        u.setPassword(encoder.encode(nueva));
        repo.save(u);
        result.put("ok", true);
        result.put("message", "Contraseña actualizada correctamente");
        return result;
    }

    // =============================================
    // SOLICITAR SER COLABORADOR
    // =============================================
    @Transactional
    public Map<String, Object> solicitarColaborador(Long userId, String motivo) {
        Map<String, Object> result = new HashMap<>();
        Usuario u = repo.findById(userId).orElseThrow();

        if (u.getRol() == com.bpm.music.models.Usuario.Rol.ADMIN) {
            result.put("error", "Los administradores no necesitan solicitar"); return result;
        }
        if (u.getRol() == com.bpm.music.models.Usuario.Rol.COLABORADOR) {
            result.put("error", "Ya eres colaborador"); return result;
        }
        if (u.getSolicitudColaborador() == com.bpm.music.models.Usuario.EstadoSolicitud.PENDIENTE) {
            result.put("error", "Ya tienes una solicitud pendiente"); return result;
        }

        u.setSolicitudColaborador(com.bpm.music.models.Usuario.EstadoSolicitud.PENDIENTE);
        u.setMotivoSolicitud(motivo != null ? motivo.trim() : "");
        u.setFechaSolicitud(java.time.LocalDateTime.now());
        repo.save(u);

        result.put("ok", true);
        result.put("message", "Solicitud enviada. El administrador la revisará pronto.");
        return result;
    }

    // =============================================
    // ACTUALIZAR PERFIL COMPLETO
    // =============================================
    @Transactional
    public Map<String, Object> actualizarPerfilCompleto(Long userId, Map<String, String> datos) {
        Usuario u = repo.findById(userId).orElseThrow();
        if (datos.containsKey("nombreDisplay") && !datos.get("nombreDisplay").isBlank())
            u.setNombreDisplay(datos.get("nombreDisplay").trim());
        if (datos.containsKey("fotoPerfil") && !datos.get("fotoPerfil").isBlank())
            u.setFotoPerfil(datos.get("fotoPerfil").trim());
        repo.save(u);
        return Map.of("ok", true, "usuario", _toPublicMap(u));
    }

    private void _enviarVerificacionNuevoEmail(String email, String username, String token) {
        try {
            org.springframework.mail.SimpleMailMessage msg = new org.springframework.mail.SimpleMailMessage();
            msg.setFrom(fromEmail);
            msg.setTo(email);
            msg.setSubject("SpaceMusic - Confirmar cambio de email");
            msg.setText("Hola " + username + ",\n\n" +
                    "Haz clic para confirmar tu nuevo email:\n" +
                    appBaseUrl + "/api/auth/confirm-email?token=" + token + "\n\n" +
                    "El enlace expira en 24 horas.\n\n— SpaceMusic");
            mailSender.send(msg);
        } catch (Exception e) {
            System.err.println("Email error: " + e.getMessage());
        }
    }

    private void _enviarVerificacion(String email, String username, String token) {
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(fromEmail);
            msg.setTo(email);
            msg.setSubject("SpaceMusic - Verifica tu cuenta");
            msg.setText(
                    "Hola " + username + ",\n\n" +
                            "Gracias por registrarte en SpaceMusic. " +
                            "Haz clic en el enlace para verificar tu cuenta:\n\n" +
                            appBaseUrl + "/api/auth/verify?token=" + token + "\n\n" +
                            "Si no creaste esta cuenta, ignora este mensaje.\n\n" +
                            "— SpaceMusic"
            );
            mailSender.send(msg);
        } catch (Exception e) {
            System.err.println("⚠️ Error enviando email de verificación: " + e.getMessage());
        }
    }

    private void _enviarReset(String email, String username, String token) {
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(fromEmail);
            msg.setTo(email);
            msg.setSubject("SpaceMusic - Recuperar contraseña");
            msg.setText(
                    "Hola " + username + ",\n\n" +
                            "Recibimos una solicitud para restablecer tu contraseña.\n" +
                            "Haz clic en el enlace (válido por 2 horas):\n\n" +
                            appBaseUrl + "/reset-password.html?token=" + token + "\n\n" +
                            "Si no solicitaste esto, ignora este mensaje. Tu contraseña no cambiará.\n\n" +
                            "— SpaceMusic"
            );
            mailSender.send(msg);
        } catch (Exception e) {
            System.err.println("⚠️ Error enviando email de reset: " + e.getMessage());
        }
    }
}