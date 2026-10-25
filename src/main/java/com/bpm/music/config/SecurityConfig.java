package com.bpm.music.config;

import com.bpm.music.security.JwtFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Autowired
    private JwtFilter jwtFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth

                // ── Estáticos ──
                .requestMatchers(
                    "/", "/*.html", "/css/**", "/js/**",
                    "/images/**", "/favicon.ico"
                ).permitAll()

                // ── Auth ──
                .requestMatchers("/api/auth/**").permitAll()

                // ── Audio y portadas (el <audio> no puede enviar headers) ──
                .requestMatchers(
                    "/api/canciones/*/audio",
                    "/api/portada/**",
                    "/api/playlists/imagen/**",
                    "/api/files/**",
                    "/api/usuario/avatar/**"
                ).permitAll()

                // ── Solo ADMIN ──
                .requestMatchers("/api/admin/**")
                    .hasRole("ADMIN")

                // ── ADMIN y COLABORADOR ──
                .requestMatchers(
                    "/api/letras/subir/**",
                    "/api/canciones/subir/**",
                    "/api/canciones/*/editar"
                ).hasAnyRole("ADMIN", "COLABORADOR")

                // ── Estadísticas globales: solo ADMIN ──
                .requestMatchers("/api/estadisticas/**")
                    .hasRole("ADMIN")

                // ── Todo lo demás requiere login ──
                .requestMatchers("/api/**").authenticated()

                .anyRequest().permitAll()
            )
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
