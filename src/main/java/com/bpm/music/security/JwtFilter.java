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

import java.io.IOException;
import java.util.List;
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

    public JwtFilter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
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
                    String rol = claims.get("rol", String.class);
                    var auth = new UsernamePasswordAuthenticationToken(
                            userId, null, List.of(new SimpleGrantedAuthority("ROLE_" + rol)));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                } catch (NumberFormatException ignored) {
                    // token con subject inválido: se trata como anónimo
                }
            }
        }

        chain.doFilter(req, res);
    }
}
