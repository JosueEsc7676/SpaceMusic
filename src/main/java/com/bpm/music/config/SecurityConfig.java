package com.bpm.music.config;

import com.bpm.music.security.JwtFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

import java.nio.charset.StandardCharsets;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /** Política de contenido: solo scripts propios; fuentes de Google opcionales. */
    private static final String CSP = String.join("; ",
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com",
            "font-src 'self' https://fonts.gstatic.com data:",
            "img-src 'self' data: blob: https:",
            "media-src 'self' blob:",
            "connect-src 'self'",
            "frame-ancestors 'self'",
            "base-uri 'self'",
            "form-action 'self'");

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtFilter jwtFilter) throws Exception {
        http
            .csrf(csrf -> csrf.disable()) // API sin cookies: el token va en la cabecera
            .cors(cors -> cors.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .headers(h -> h
                .contentSecurityPolicy(csp -> csp.policyDirectives(CSP))
                .frameOptions(f -> f.sameOrigin())
                .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN)))
            .authorizeHttpRequests(auth -> auth
                // ── Perfil propio: requiere sesión ──
                .requestMatchers("/api/auth/me", "/api/auth/me/**", "/api/auth/refresh").authenticated()
                // ── Registro, login, recuperación ──
                .requestMatchers("/api/auth/**").permitAll()
                // ── Imágenes públicas (portadas y avatares) ──
                .requestMatchers(HttpMethod.GET,
                        "/api/portada/**",
                        "/api/playlists/imagen/**",
                        "/api/usuario/avatar/**",
                        "/api/perfiles/banner/**",
                        "/api/auth/avatar/**").permitAll()
                // ── Administración ──
                .requestMatchers("/api/admin/**", "/api/estadisticas/**").hasRole("ADMIN")
                // ── Resto de la API: usuario logueado ──
                .requestMatchers("/api/**").authenticated()
                // ── Frontend estático ──
                .anyRequest().permitAll()
            )
            .exceptionHandling(e -> e
                .authenticationEntryPoint((req, res, ex) -> escribirError(res, 401, "Debes iniciar sesión"))
                .accessDeniedHandler((req, res, ex) -> escribirError(res, 403, "No tienes permiso para esto")))
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /** Evita que Spring registre el filtro JWT una segunda vez fuera de la cadena de seguridad. */
    @Bean
    public FilterRegistrationBean<JwtFilter> jwtFilterRegistration(JwtFilter filter) {
        FilterRegistrationBean<JwtFilter> reg = new FilterRegistrationBean<>(filter);
        reg.setEnabled(false);
        return reg;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    private static void escribirError(jakarta.servlet.http.HttpServletResponse res, int status, String msg)
            throws java.io.IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        res.getWriter().write("{\"error\":\"" + msg + "\"}");
    }
}
