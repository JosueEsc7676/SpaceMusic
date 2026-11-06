package com.bpm.music.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.bpm.music.models.Usuario;
import com.bpm.music.repositories.UsuarioRepository;
import java.io.IOException;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Lee el token JWT de la cabecera Authorization.
 * Para el audio (la etiqueta &lt;audio&gt; no puede enviar cabeceras) también
 * acepta el token en el parámetro ?t=
 */
@Component
public class JwtFilter extends OncePerRequestFilter {

    private static final Pattern RUTAS_CON_TOKEN_EN_URL =
            Pattern.compile("^/api/canciones/\\d+/(audio|descargar)$|^/api/eventos$"); // eventos en vivo: EventSource no envía cabeceras

    private final JwtUtil jwtUtil;
    private final UsuarioRepository usuarios;

    /** Estado actual de cada cuenta (se revisa en la base de datos como máximo cada 20 s). */
    private record Cuenta(boolean valida, String rol, long sesionesDesde, long leida) {}
    private static final Map<Long, Cuenta> CUENTAS = new ConcurrentHashMap<>();
    private static final long VIGENCIA_MS = 20_000;

    public JwtFilter(JwtUtil jwtUtil, UsuarioRepository usuarios) {
        this.jwtUtil = jwtUtil;
        this.usuarios = usuarios;
    }

    /** Fuerza a releer la cuenta en la próxima petición (al desactivarla, cambiar su rol o su contraseña). */
    public static void olvidar(Long userId) {
        if (userId != null) CUENTAS.remove(userId);
    }

    private Cuenta cuenta(Long id) {
        Cuenta c = CUENTAS.get(id);
        if (c != null && System.currentTimeMillis() - c.leida() < VIGENCIA_MS) return c;
        Usuario u = usuarios.findById(id).orElse(null);
        c = u == null ? new Cuenta(false, null, 0, System.currentTimeMillis())
                : new Cuenta(u.isActivo() && u.getEstadoCuenta() != Usuario.EstadoCuenta.RECHAZADA, u.getRol().name(),
                        u.getSesionesDesde() == null ? 0 : u.getSesionesDesde().atZone(ZoneId.systemDefault()).toEpochSecond(),
                        System.currentTimeMillis());
        CUENTAS.put(id, c);
        return c;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        String token = null;
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            token = header.substring(7).trim();
        } else if ("GET".equals(req.getMethod()) || "HEAD".equals(req.getMethod())) {
            String t = req.getParameter("t");
            if (t != null && RUTAS_CON_TOKEN_EN_URL.matcher(req.getRequestURI()).matches()) {
                token = t;
            }
        }

        if (token != null && !token.isEmpty()) {
            Claims claims = jwtUtil.parse(token);
            if (claims != null) {
                try {
                    Long userId = Long.parseLong(claims.getSubject());
                    // El rol y el estado se toman de la cuenta actual, no del token: si se desactiva
                    // la cuenta, se le quita un rol o cambia su contraseña, el token viejo deja de valer.
                    Cuenta c = cuenta(userId);
                    long emitido = claims.getIssuedAt() == null ? 0 : claims.getIssuedAt().getTime() / 1000;
                    if (c.valida() && emitido >= c.sesionesDesde()) {
                        var auth = new UsernamePasswordAuthenticationToken(
                                userId, null, List.of(new SimpleGrantedAuthority("ROLE_" + c.rol())));
                        SecurityContextHolder.getContext().setAuthentication(auth);
                    }
                } catch (NumberFormatException ignored) {
                    // token con subject inválido: se trata como anónimo
                }
            }
        }

        chain.doFilter(req, res);
    }
}
