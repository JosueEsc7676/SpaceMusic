package com.bpm.music.services;

import com.bpm.music.models.CuentaExterna;
import com.bpm.music.models.Usuario;
import com.bpm.music.repositories.CuentaExternaRepository;
import com.bpm.music.repositories.UsuarioRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Entrar con Google, Facebook o Apple (OAuth con el servidor como cliente confidencial):
 * 1) la app abre {@code /api/auth/oauth/{proveedor}/inicio?app=web|ios|android} → el servicio pide permiso a la persona;
 * 2) el servicio vuelve a {@code /api/auth/oauth/{proveedor}/vuelta} con un código; aquí se cambia por los datos
 *    (correo verificado e identificador) directamente al servicio, por https;
 * 3) se busca la cuenta enlazada; si no hay, la del mismo correo; si tampoco, se crea una cuenta nueva que espera la
 *    aprobación de un administrador (como el registro normal);
 * 4) se vuelve a la app con la sesión: web → {@code /login.html#oauth=TOKEN}; iPhone y Android → {@code spacemusic://oauth?token=TOKEN}.
 * Cada proveedor solo aparece si tiene sus claves configuradas (variables de entorno o {@code config/application.properties}).
 * El «state» va firmado (HMAC) y caduca a los 10 minutos: nadie puede inventar una vuelta.
 */
@Service
public class OAuthService {

    private static final Logger log = LoggerFactory.getLogger(OAuthService.class);
    public static final Set<String> PROVEEDORES = Set.of("google", "facebook", "apple");
    private static final Set<String> APPS = Set.of("web", "ios", "android");

    private final UsuarioRepository usuarios;
    private final CuentaExternaRepository cuentas;
    private final PasswordEncoder encoder;
    private final AuthService auth;
    private final MailService mail;
    private final String base;
    private final Map<String, String[]> claves = new HashMap<>(); // proveedor → {id, secreto}
    private final String appleEquipo, appleClave, appleArchivo;
    private final byte[] firma = new byte[32];
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public OAuthService(UsuarioRepository usuarios, CuentaExternaRepository cuentas, PasswordEncoder encoder, AuthService auth, MailService mail,
                        @Value("${app.base-url:http://localhost:8080}") String base,
                        @Value("${auth.google.client-id:${GOOGLE_CLIENT_ID:}}") String googleId,
                        @Value("${auth.google.client-secret:${GOOGLE_CLIENT_SECRET:}}") String googleSecreto,
                        @Value("${auth.facebook.app-id:${FACEBOOK_APP_ID:}}") String facebookId,
                        @Value("${auth.facebook.app-secret:${FACEBOOK_APP_SECRET:}}") String facebookSecreto,
                        @Value("${auth.apple.client-id:${APPLE_CLIENT_ID:}}") String appleId,
                        @Value("${auth.apple.team-id:${APPLE_TEAM_ID:}}") String appleEquipo,
                        @Value("${auth.apple.key-id:${APPLE_KEY_ID:}}") String appleClave,
                        @Value("${auth.apple.key-file:${APPLE_KEY_FILE:}}") String appleArchivo) {
        this.usuarios = usuarios;
        this.cuentas = cuentas;
        this.encoder = encoder;
        this.auth = auth;
        this.mail = mail;
        this.base = base.replaceAll("/+$", "");
        claves.put("google", new String[]{googleId.trim(), googleSecreto.trim()});
        claves.put("facebook", new String[]{facebookId.trim(), facebookSecreto.trim()});
        claves.put("apple", new String[]{appleId.trim(), ""});
        this.appleEquipo = appleEquipo.trim();
        this.appleClave = appleClave.trim();
        this.appleArchivo = appleArchivo.trim();
        new SecureRandom().nextBytes(firma);
    }

    public boolean configurado(String p) {
        String[] c = claves.get(p);
        if (c == null || c[0].isEmpty()) return false;
        if ("apple".equals(p)) return !appleEquipo.isEmpty() && !appleClave.isEmpty() && Files.isRegularFile(Path.of(appleArchivo));
        return !c[1].isEmpty();
    }

    /** Qué botones mostrar. */
    public Map<String, Boolean> proveedores() {
        Map<String, Boolean> r = new LinkedHashMap<>();
        for (String p : List.of("google", "facebook", "apple")) r.put(p, configurado(p));
        return r;
    }

    private String vuelta(String p) { return base + "/api/auth/oauth/" + p + "/vuelta"; }
    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    // ── state firmado ──

    String firmar(String datos) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(firma, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(m.doFinal(datos.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    String crearState(String p, String app) {
        String datos = p + "|" + app + "|" + (System.currentTimeMillis() + 10 * 60_000L) + "|" + UUID.randomUUID();
        String b = Base64.getUrlEncoder().withoutPadding().encodeToString(datos.getBytes(StandardCharsets.UTF_8));
        return b + "." + firmar(datos);
    }

    /** Devuelve la app de destino si el state es válido, del mismo proveedor y no caducó; si no, null. */
    String leerState(String p, String state) {
        if (state == null || !state.contains(".")) return null;
        try {
            String[] partes = state.split("\\.", 2);
            String datos = new String(Base64.getUrlDecoder().decode(partes[0]), StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(firmar(datos).getBytes(StandardCharsets.UTF_8), partes[1].getBytes(StandardCharsets.UTF_8))) return null;
            String[] d = datos.split("\\|");
            if (d.length < 4 || !d[0].equals(p) || Long.parseLong(d[2]) < System.currentTimeMillis()) return null;
            return APPS.contains(d[1]) ? d[1] : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Dirección del servicio para pedir permiso a la persona. */
    public String urlInicio(String p, String app) {
        if (!PROVEEDORES.contains(p) || !configurado(p)) throw new IllegalArgumentException("Ese inicio de sesión no está configurado en este servidor");
        String a = APPS.contains(app) ? app : "web";
        String state = enc(crearState(p, a)), id = enc(claves.get(p)[0]), r = enc(vuelta(p));
        return switch (p) {
            case "google" -> "https://accounts.google.com/o/oauth2/v2/auth?response_type=code&scope=" + enc("openid email profile")
                    + "&prompt=select_account&client_id=" + id + "&redirect_uri=" + r + "&state=" + state;
            case "facebook" -> "https://www.facebook.com/v19.0/dialog/oauth?response_type=code&scope=" + enc("email,public_profile")
                    + "&client_id=" + id + "&redirect_uri=" + r + "&state=" + state;
            default -> "https://appleid.apple.com/auth/authorize?response_type=code&response_mode=form_post&scope=" + enc("name email")
                    + "&client_id=" + id + "&redirect_uri=" + r + "&state=" + state;
        };
    }

    /** Datos de la persona según el servicio. */
    record Persona(String sujeto, String email, boolean verificado, String nombre) {}

    private JsonNode post(String url, String cuerpo) throws Exception {
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(cuerpo)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IllegalStateException("El servicio respondió " + res.statusCode());
        return json.readTree(res.body());
    }

    private JsonNode get(String url) throws Exception {
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IllegalStateException("El servicio respondió " + res.statusCode());
        return json.readTree(res.body());
    }

    /** Datos del token de identidad (llega directo del servicio por https, en la respuesta a nuestra petición). */
    static JsonNode cargaJwt(String jwt) throws Exception {
        String[] partes = jwt.split("\\.");
        return new ObjectMapper().readTree(Base64.getUrlDecoder().decode(partes[1]));
    }

    private Persona google(String code) throws Exception {
        String[] c = claves.get("google");
        JsonNode t = post("https://oauth2.googleapis.com/token", "grant_type=authorization_code&code=" + enc(code)
                + "&client_id=" + enc(c[0]) + "&client_secret=" + enc(c[1]) + "&redirect_uri=" + enc(vuelta("google")));
        JsonNode d = cargaJwt(t.path("id_token").asText());
        if (!c[0].equals(d.path("aud").asText())) throw new IllegalStateException("Token de otra aplicación");
        return new Persona(d.path("sub").asText(), d.path("email").asText(""), d.path("email_verified").asBoolean(false), d.path("name").asText(""));
    }

    private Persona facebook(String code) throws Exception {
        String[] c = claves.get("facebook");
        JsonNode t = get("https://graph.facebook.com/v19.0/oauth/access_token?client_id=" + enc(c[0]) + "&client_secret=" + enc(c[1])
                + "&redirect_uri=" + enc(vuelta("facebook")) + "&code=" + enc(code));
        String token = t.path("access_token").asText();
        JsonNode me = get("https://graph.facebook.com/v19.0/me?fields=id,name,email&access_token=" + enc(token));
        // Facebook solo entrega correos confirmados
        return new Persona(me.path("id").asText(), me.path("email").asText(""), !me.path("email").asText("").isEmpty(), me.path("name").asText(""));
    }

    /** Secreto de Apple: un JWT ES256 firmado con la clave .p8 de la cuenta de desarrollador (dura 5 minutos). */
    private String secretoApple() throws Exception {
        String pem = Files.readString(Path.of(appleArchivo)).replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        PrivateKey k = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
        Date ahora = new Date();
        return Jwts.builder().header().keyId(appleClave).and()
                .issuer(appleEquipo).issuedAt(ahora).expiration(new Date(ahora.getTime() + 300_000))
                .audience().add("https://appleid.apple.com").and().subject(claves.get("apple")[0])
                .signWith(k, Jwts.SIG.ES256).compact();
    }

    private Persona apple(String code, String usuarioJson) throws Exception {
        String id = claves.get("apple")[0];
        JsonNode t = post("https://appleid.apple.com/auth/token", "grant_type=authorization_code&code=" + enc(code)
                + "&client_id=" + enc(id) + "&client_secret=" + enc(secretoApple()) + "&redirect_uri=" + enc(vuelta("apple")));
        JsonNode d = cargaJwt(t.path("id_token").asText());
        if (!id.equals(d.path("aud").asText())) throw new IllegalStateException("Token de otra aplicación");
        String nombre = "";
        try {
            JsonNode u = usuarioJson == null ? null : json.readTree(usuarioJson).path("name");
            if (u != null) nombre = (u.path("firstName").asText("") + " " + u.path("lastName").asText("")).trim();
        } catch (Exception ignored) { }
        boolean verificado = d.path("email_verified").asBoolean(false) || "true".equals(d.path("email_verified").asText());
        return new Persona(d.path("sub").asText(), d.path("email").asText(""), verificado, nombre);
    }

    /** Usuario nuevo a partir del correo (usuario sin repetir, contraseña aleatoria; espera aprobación). */
    private Usuario crear(String p, Persona per) {
        String raiz = per.email().split("@")[0].replaceAll("[^A-Za-z0-9_.]", "").toLowerCase(Locale.ROOT);
        if (raiz.length() < 3) raiz = "usuario";
        if (raiz.length() > 20) raiz = raiz.substring(0, 20);
        String u = raiz;
        for (int i = 2; usuarios.existsByUsernameIgnoreCase(u); i++) u = raiz + i;
        byte[] azar = new byte[24];
        new SecureRandom().nextBytes(azar);
        String aprobacion = UUID.randomUUID().toString().replace("-", "");
        Usuario n = Usuario.builder()
                .email(per.email().toLowerCase(Locale.ROOT)).username(u)
                .password(encoder.encode(Base64.getEncoder().encodeToString(azar)))
                .nombreDisplay(per.nombre().isBlank() ? u : per.nombre().length() > 100 ? per.nombre().substring(0, 100) : per.nombre())
                .rol(Usuario.Rol.USER).emailVerificado(true)
                .estadoCuenta(Usuario.EstadoCuenta.PENDIENTE).aprobacionToken(aprobacion).build();
        usuarios.save(n);
        for (Usuario admin : usuarios.findByRolAndActivoTrue(Usuario.Rol.ADMIN)) {
            try { mail.nuevaCuentaPendiente(admin.getEmail(), admin.getNombreVisible(), n.getNombreVisible(), n.getUsername(), n.getEmail(), aprobacion); }
            catch (Exception ignored) { }
        }
        return n;
    }

    /** La persona ya identificada: su usuario (enlazado, por correo o nuevo). */
    @Transactional
    public Usuario usuarioPara(String p, Persona per) {
        Optional<CuentaExterna> enlazada = cuentas.findByProveedorAndSujeto(p, per.sujeto());
        if (enlazada.isPresent()) return usuarios.findById(enlazada.get().getUsuarioId()).orElseThrow();
        if (per.email().isBlank() || !per.verificado())
            throw new IllegalArgumentException("Tu cuenta de " + nombre(p) + " no tiene un correo verificado");
        Usuario u = usuarios.findByEmailIgnoreCase(per.email()).orElseGet(() -> crear(p, per));
        cuentas.save(new CuentaExterna(p, per.sujeto(), u.getId()));
        return u;
    }

    static String nombre(String p) { return switch (p) { case "google" -> "Google"; case "facebook" -> "Facebook"; default -> "Apple"; }; }

    /** Termina el inicio de sesión y devuelve a dónde volver (con la sesión o con el error). */
    public String completar(String p, String code, String state, String usuarioApple, String error) {
        String app = leerState(p, state);
        if (app == null) return destino("web", null, "El inicio de sesión caducó. Inténtalo otra vez.");
        if (error != null && !error.isBlank() || code == null || code.isBlank()) return destino(app, null, "Cancelaste el inicio de sesión con " + nombre(p));
        try {
            Persona per = switch (p) {
                case "google" -> google(code);
                case "facebook" -> facebook(code);
                default -> apple(code, usuarioApple);
            };
            Usuario u = usuarioPara(p, per);
            if (!u.isActivo()) return destino(app, null, "Tu cuenta está desactivada. Contacta al administrador.");
            if (u.getEstadoCuenta() == Usuario.EstadoCuenta.PENDIENTE)
                return destino(app, null, "Tu cuenta quedó creada y espera la aprobación de un administrador. Te avisaremos por correo.");
            if (u.getEstadoCuenta() == Usuario.EstadoCuenta.RECHAZADA) return destino(app, null, "Tu solicitud de cuenta no fue aprobada.");
            u.setUltimoLogin(LocalDateTime.now());
            usuarios.save(u);
            return destino(app, (String) auth.sesionPara(u).get("token"), null);
        } catch (IllegalArgumentException e) {
            return destino(app, null, e.getMessage());
        } catch (Exception e) {
            log.warn("Inicio de sesión con {} falló: {}", p, e.getMessage());
            return destino(app, null, "No se pudo entrar con " + nombre(p) + ". Inténtalo otra vez.");
        }
    }

    private String destino(String app, String token, String error) {
        String q = token != null ? "token=" + enc(token) : "error=" + enc(error);
        return "web".equals(app) ? "/login.html#oauth&" + q : "spacemusic://oauth?" + q;
    }
}
