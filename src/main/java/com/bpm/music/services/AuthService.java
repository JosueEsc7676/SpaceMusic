package com.bpm.music.services;

import com.bpm.music.models.Usuario;
import com.bpm.music.repositories.UsuarioRepository;
import com.bpm.music.security.JwtUtil;
import com.bpm.music.security.LoginRateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class AuthService {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$");
    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9._-]{3,30}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UsuarioRepository repo;
    private final PasswordEncoder encoder;
    private final JwtUtil jwtUtil;
    private final MailService mail;
    private final LoginRateLimiter limiter;
    private final String setupKey;
    private final boolean requiereVerificacion;

    public AuthService(UsuarioRepository repo, PasswordEncoder encoder, JwtUtil jwtUtil, MailService mail,
                       LoginRateLimiter limiter,
                       @Value("${app.setup-key:}") String setupKey,
                       @Value("${app.require-email-verification:true}") boolean requiereVerificacion) {
        this.repo = repo;
        this.encoder = encoder;
        this.jwtUtil = jwtUtil;
        this.mail = mail;
        this.limiter = limiter;
        this.setupKey = setupKey;
        this.requiereVerificacion = requiereVerificacion;
    }

    // ═══════════════════════ Estado inicial ═══════════════════════

    public Map<String, Object> estado() {
        boolean hayAdmin = repo.existsByRol(Usuario.Rol.ADMIN);
        return Map.of(
                "necesitaAdmin", !hayAdmin,
                "requiereClave", !setupKey.isBlank(),
                "requiereVerificacion", requiereVerificacion && mail.configurado());
    }

    // ═══════════════════════ Registro ═══════════════════════

    @Transactional
    public Map<String, Object> registrar(String email, String username, String password, String nombreDisplay) {
        String error = validar(email, username, password);
        if (error != null) return Map.of("error", error);
        email = email.trim().toLowerCase(Locale.ROOT);
        username = username.trim();

        if (repo.existsByEmailIgnoreCase(email)) return Map.of("error", "El correo ya está registrado");
        if (repo.existsByUsernameIgnoreCase(username)) return Map.of("error", "Ese nombre de usuario ya está en uso");

        // El usuario no confirma su correo: queda registrado al instante y
        // los administradores reciben un correo para aprobar la cuenta.
        String token = token();
        Usuario u = Usuario.builder()
                .email(email)
                .username(username)
                .password(encoder.encode(password))
                .nombreDisplay(recortar(nombreDisplay, 100, username))
                .rol(Usuario.Rol.USER)
                .emailVerificado(true)
                .estadoCuenta(Usuario.EstadoCuenta.PENDIENTE)
                .aprobacionToken(token)
                .build();
        repo.save(u);

        for (Usuario admin : repo.findByRolAndActivoTrue(Usuario.Rol.ADMIN)) {
            mail.nuevaCuentaPendiente(admin.getEmail(), admin.getNombreVisible(),
                    u.getNombreVisible(), u.getUsername(), u.getEmail(), token);
        }
        return Map.of("ok", true, "pendiente", true,
                "message", "¡Cuenta creada! Un administrador la revisará en breve y te avisaremos por correo cuando puedas entrar.");
    }

    // ═══════════════════════ Aprobación de cuentas ═══════════════════════

    /** Datos de la cuenta que se va a aprobar desde el enlace del correo. */
    public Map<String, Object> cuentaPorToken(String token) {
        if (token == null || token.isBlank()) return Map.of("error", "Enlace inválido");
        return repo.findByAprobacionToken(token)
                .<Map<String, Object>>map(u -> Map.of("ok", true, "usuario", Map.of(
                        "username", u.getUsername(), "email", u.getEmail(),
                        "nombre", u.getNombreVisible(), "estado", u.getEstadoCuenta().name(),
                        "fechaRegistro", String.valueOf(u.getFechaRegistro()))))
                .orElse(Map.of("error", "Este enlace ya se usó o no es válido. Revisa el panel de administración."));
    }

    @Transactional
    public Map<String, Object> resolverPorToken(String token, boolean aprobar) {
        if (token == null || token.isBlank()) return Map.of("error", "Enlace inválido");
        Optional<Usuario> opt = repo.findByAprobacionToken(token);
        if (opt.isEmpty()) return Map.of("error", "Este enlace ya se usó o no es válido.");
        return resolverCuenta(opt.get(), aprobar);
    }

    @Transactional
    public Map<String, Object> resolverCuenta(Usuario u, boolean aprobar) {
        u.setEstadoCuenta(aprobar ? Usuario.EstadoCuenta.APROBADA : Usuario.EstadoCuenta.RECHAZADA);
        u.setAprobacionToken(null);
        repo.save(u);
        mail.cuentaResuelta(u.getEmail(), u.getNombreVisible(), aprobar);
        return Map.of("ok", true, "estado", u.getEstadoCuenta().name(),
                "message", aprobar ? "Cuenta de " + u.getUsername() + " aprobada" : "Cuenta de " + u.getUsername() + " rechazada");
    }

    @Transactional
    public Map<String, Object> verificarEmail(String token) {
        if (token == null || token.isBlank()) return Map.of("error", "Enlace inválido");
        Optional<Usuario> opt = repo.findByVerificationToken(token);
        if (opt.isEmpty()) return Map.of("error", "El enlace no es válido o ya se usó");
        Usuario u = opt.get();
        u.setEmailVerificado(true);
        u.setVerificationToken(null);
        repo.save(u);
        return Map.of("ok", true);
    }

    @Transactional
    public Map<String, Object> reenviarVerificacion(String email) {
        Map<String, Object> ok = Map.of("ok", true, "message", "Si la cuenta existe y no está verificada, te enviamos un correo.");
        if (email == null) return ok;
        repo.findByEmailIgnoreCase(email.trim()).filter(u -> !u.isEmailVerificado()).ifPresent(u -> {
            String t = token();
            u.setVerificationToken(t);
            repo.save(u);
            mail.verificacion(u.getEmail(), u.getNombreVisible(), t);
        });
        return ok;
    }

    // ═══════════════════════ Login ═══════════════════════

    @Transactional
    public Map<String, Object> login(String usuarioOEmail, String password, String ip) {
        if (usuarioOEmail == null || usuarioOEmail.isBlank() || password == null || password.isEmpty()) {
            return Map.of("error", "Escribe tu usuario y contraseña");
        }
        long espera = limiter.segundosBloqueado(ip, usuarioOEmail);
        if (espera > 0) {
            return Map.of("error", "Demasiados intentos fallidos. Intenta de nuevo en " + Math.max(1, espera / 60) + " min.");
        }
        String id = usuarioOEmail.trim();
        Optional<Usuario> opt = id.contains("@") ? repo.findByEmailIgnoreCase(id) : repo.findByUsernameIgnoreCase(id);

        if (opt.isEmpty() || !encoder.matches(password, opt.get().getPassword())) {
            limiter.registrarFallo(ip, usuarioOEmail);
            return Map.of("error", "Usuario o contraseña incorrectos");
        }
        Usuario u = opt.get();
        if (!u.isActivo()) return Map.of("error", "Tu cuenta está desactivada. Contacta al administrador.");
        if (u.getEstadoCuenta() == Usuario.EstadoCuenta.PENDIENTE) {
            return Map.of("error", "Tu cuenta está esperando la aprobación de un administrador. Te avisaremos por correo.", "pendiente", true);
        }
        if (u.getEstadoCuenta() == Usuario.EstadoCuenta.RECHAZADA) {
            return Map.of("error", "Tu solicitud de cuenta no fue aprobada. Contacta al administrador.");
        }

        limiter.registrarExito(ip, usuarioOEmail);
        u.setUltimoLogin(LocalDateTime.now());
        repo.save(u);
        return sesion(u);
    }

    /** Token nuevo con el rol actual (por si un admin lo cambió). */
    public Map<String, Object> refrescar(Long userId) {
        Usuario u = repo.findById(userId).orElseThrow(() -> new NoSuchElementException("Usuario no encontrado"));
        if (!u.isActivo()) return Map.of("error", "Tu cuenta está desactivada");
        if (u.getEstadoCuenta() != Usuario.EstadoCuenta.APROBADA) return Map.of("error", "Tu cuenta no está aprobada");
        return sesion(u);
    }

    private Map<String, Object> sesion(Usuario u) {
        Map<String, Object> r = new HashMap<>();
        r.put("ok", true);
        r.put("token", jwtUtil.generateToken(u.getId(), u.getUsername(), u.getRol().name()));
        r.put("usuario", perfilPublico(u));
        return r;
    }

    // ═══════════════════════ Recuperar contraseña ═══════════════════════

    @Transactional
    public Map<String, Object> solicitarReset(String email) {
        Map<String, Object> ok = Map.of("ok", true, "message", "Si el correo está registrado, recibirás un enlace para cambiar la contraseña.");
        if (email == null || email.isBlank()) return ok;
        repo.findByEmailIgnoreCase(email.trim()).filter(Usuario::isActivo).ifPresent(u -> {
            String t = token();
            u.setResetToken(t);
            u.setResetTokenExpiry(LocalDateTime.now().plusHours(2));
            repo.save(u);
            mail.recuperacion(u.getEmail(), u.getNombreVisible(), t);
        });
        return ok;
    }

    @Transactional
    public Map<String, Object> resetPassword(String token, String nueva) {
        if (token == null || token.isBlank()) return Map.of("error", "Enlace inválido");
        Optional<Usuario> opt = repo.findByResetToken(token);
        if (opt.isEmpty()) return Map.of("error", "El enlace no es válido o ya se usó");
        Usuario u = opt.get();
        if (u.getResetTokenExpiry() == null || LocalDateTime.now().isAfter(u.getResetTokenExpiry())) {
            return Map.of("error", "El enlace expiró. Solicita uno nuevo.");
        }
        String err = validarPassword(nueva);
        if (err != null) return Map.of("error", err);
        u.setPassword(encoder.encode(nueva));
        u.setResetToken(null);
        u.setResetTokenExpiry(null);
        u.setEmailVerificado(true); // si recibió el correo, el correo es suyo
        repo.save(u);
        return Map.of("ok", true, "message", "Contraseña actualizada. Ya puedes iniciar sesión.");
    }

    // ═══════════════════════ Perfil ═══════════════════════

    public Map<String, Object> perfil(Long userId) {
        return perfilPublico(repo.findById(userId).orElseThrow(() -> new NoSuchElementException("Usuario no encontrado")));
    }

    @Transactional
    public Map<String, Object> actualizarPerfil(Long userId, String nombreDisplay) {
        Usuario u = repo.findById(userId).orElseThrow();
        if (nombreDisplay != null && !nombreDisplay.isBlank()) u.setNombreDisplay(recortar(nombreDisplay, 100, u.getUsername()));
        repo.save(u);
        return Map.of("ok", true, "usuario", perfilPublico(u));
    }

    @Transactional
    public Map<String, Object> cambiarPassword(Long userId, String actual, String nueva) {
        Usuario u = repo.findById(userId).orElseThrow();
        if (actual == null || !encoder.matches(actual, u.getPassword())) return Map.of("error", "La contraseña actual no es correcta");
        String err = validarPassword(nueva);
        if (err != null) return Map.of("error", err);
        if (encoder.matches(nueva, u.getPassword())) return Map.of("error", "La nueva contraseña debe ser distinta");
        u.setPassword(encoder.encode(nueva));
        repo.save(u);
        return Map.of("ok", true, "message", "Contraseña actualizada");
    }

    @Transactional
    public Map<String, Object> cambiarEmail(Long userId, String nuevoEmail, String password) {
        Usuario u = repo.findById(userId).orElseThrow();
        if (password == null || !encoder.matches(password, u.getPassword())) return Map.of("error", "Contraseña incorrecta");
        if (nuevoEmail == null || !EMAIL.matcher(nuevoEmail.trim()).matches()) return Map.of("error", "Escribe un correo válido");
        String email = nuevoEmail.trim().toLowerCase(Locale.ROOT);
        if (email.equalsIgnoreCase(u.getEmail())) return Map.of("error", "Ese ya es tu correo");
        if (repo.existsByEmailIgnoreCase(email)) return Map.of("error", "Ese correo ya está en uso");

        if (!mail.configurado()) {
            u.setEmail(email);
            repo.save(u);
            return Map.of("ok", true, "message", "Correo actualizado", "usuario", perfilPublico(u));
        }
        String t = token();
        u.setEmailPendiente(email);
        u.setVerificationToken(t);
        u.setResetTokenExpiry(LocalDateTime.now().plusHours(24));
        repo.save(u);
        mail.cambioEmail(email, u.getNombreVisible(), t);
        return Map.of("ok", true, "message", "Te enviamos un enlace a " + email + " para confirmar el cambio.");
    }

    @Transactional
    public Map<String, Object> confirmarCambioEmail(String token) {
        Optional<Usuario> opt = token == null ? Optional.empty() : repo.findByVerificationToken(token);
        if (opt.isEmpty() || opt.get().getEmailPendiente() == null) return Map.of("error", "El enlace no es válido");
        Usuario u = opt.get();
        if (u.getResetTokenExpiry() != null && LocalDateTime.now().isAfter(u.getResetTokenExpiry())) {
            return Map.of("error", "El enlace expiró");
        }
        if (repo.existsByEmailIgnoreCase(u.getEmailPendiente())) return Map.of("error", "Ese correo ya está en uso");
        u.setEmail(u.getEmailPendiente());
        u.setEmailPendiente(null);
        u.setVerificationToken(null);
        u.setResetTokenExpiry(null);
        u.setEmailVerificado(true);
        repo.save(u);
        return Map.of("ok", true);
    }

    @Transactional
    public Map<String, Object> solicitarColaborador(Long userId, String motivo) {
        Usuario u = repo.findById(userId).orElseThrow();
        if (u.getRol() != Usuario.Rol.USER) return Map.of("error", "Ya tienes permisos de colaborador");
        if (u.getSolicitudColaborador() == Usuario.EstadoSolicitud.PENDIENTE) return Map.of("error", "Ya tienes una solicitud pendiente");
        u.setSolicitudColaborador(Usuario.EstadoSolicitud.PENDIENTE);
        u.setMotivoSolicitud(recortar(motivo, 500, ""));
        u.setFechaSolicitud(LocalDateTime.now());
        u.setRespuestaAdmin(null);
        repo.save(u);
        return Map.of("ok", true, "message", "Solicitud enviada. Un administrador la revisará.", "usuario", perfilPublico(u));
    }

    @Transactional
    public void guardarFoto(Long userId, String url) {
        Usuario u = repo.findById(userId).orElseThrow();
        u.setFotoPerfil(url);
        repo.save(u);
    }

    // ═══════════════════════ Primer administrador ═══════════════════════

    @Transactional
    public Map<String, Object> crearPrimerAdmin(String email, String username, String password,
                                                String nombreDisplay, String clave, boolean desdeLocalhost) {
        if (repo.existsByRol(Usuario.Rol.ADMIN)) return Map.of("error", "Ya existe un administrador");
        if (setupKey.isBlank()) {
            if (!desdeLocalhost) return Map.of("error", "Configura app.setup-key o crea el admin desde el propio servidor (localhost)");
        } else if (clave == null || !java.security.MessageDigest.isEqual(clave.getBytes(), setupKey.getBytes())) {
            return Map.of("error", "Clave de instalación incorrecta");
        }
        String error = validar(email, username, password);
        if (error != null) return Map.of("error", error);
        if (repo.existsByEmailIgnoreCase(email.trim())) return Map.of("error", "El correo ya está registrado");
        if (repo.existsByUsernameIgnoreCase(username.trim())) return Map.of("error", "El nombre de usuario ya está en uso");

        Usuario admin = repo.save(Usuario.builder()
                .email(email.trim().toLowerCase(Locale.ROOT))
                .username(username.trim())
                .password(encoder.encode(password))
                .nombreDisplay(recortar(nombreDisplay, 100, username.trim()))
                .rol(Usuario.Rol.ADMIN)
                .activo(true)
                .emailVerificado(true)
                .build());
        Map<String, Object> r = new HashMap<>(sesion(admin));
        r.put("message", "Administrador creado");
        return r;
    }

    // ═══════════════════════ Helpers ═══════════════════════

    public static Map<String, Object> perfilPublico(Usuario u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("username", u.getUsername());
        m.put("email", u.getEmail());
        m.put("nombreDisplay", u.getNombreVisible());
        m.put("fotoPerfil", u.getFotoPerfil());
        m.put("rol", u.getRol().name());
        m.put("fechaRegistro", u.getFechaRegistro());
        m.put("ultimoLogin", u.getUltimoLogin());
        m.put("solicitudColaborador", u.getSolicitudColaborador().name());
        m.put("respuestaAdmin", u.getRespuestaAdmin());
        m.put("emailPendiente", u.getEmailPendiente());
        return m;
    }

    private String validar(String email, String username, String password) {
        if (email == null || !EMAIL.matcher(email.trim()).matches()) return "Escribe un correo válido";
        if (username == null || !USERNAME.matcher(username.trim()).matches())
            return "El usuario debe tener de 3 a 30 caracteres: letras, números, punto, guion o guion bajo";
        return validarPassword(password);
    }

    private static String validarPassword(String p) {
        if (p == null || p.length() < 8) return "La contraseña debe tener al menos 8 caracteres";
        if (p.length() > 128) return "La contraseña es demasiado larga";
        if (p.chars().noneMatch(Character::isLetter) || p.chars().noneMatch(Character::isDigit))
            return "La contraseña debe tener letras y números";
        return null;
    }

    private static String recortar(String s, int max, String porDefecto) {
        if (s == null || s.isBlank()) return porDefecto;
        s = s.trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String token() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
