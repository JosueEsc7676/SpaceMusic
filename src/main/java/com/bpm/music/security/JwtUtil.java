package com.bpm.music.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Date;

@Component
public class JwtUtil {

    private static final Logger log = LoggerFactory.getLogger(JwtUtil.class);

    private final SecretKey key;
    private final long expirationMs;

    public JwtUtil(@Value("${jwt.secret:}") String secret,
                   @Value("${jwt.expiration:604800000}") long expirationMs) {
        this.expirationMs = expirationMs;
        this.key = construirClave(secret);
    }

    private static SecretKey construirClave(String secret) {
        try {
            byte[] material;
            if (secret == null || secret.isBlank()) {
                material = new byte[64];
                new SecureRandom().nextBytes(material);
                log.warn("jwt.secret no está configurado: se generó una clave temporal. " +
                        "Las sesiones se cerrarán al reiniciar el servidor.");
            } else {
                // SHA-512 permite usar claves de cualquier longitud de forma segura
                material = MessageDigest.getInstance("SHA-512").digest(secret.getBytes(StandardCharsets.UTF_8));
            }
            return Keys.hmacShaKeyFor(material);
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo crear la clave JWT", e);
        }
    }

    public String generateToken(Long userId, String username, String rol) {
        Date ahora = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("rol", rol)
                .issuedAt(ahora)
                .expiration(new Date(ahora.getTime() + expirationMs))
                .signWith(key)
                .compact();
    }

    /** Devuelve los claims si el token es válido, o null si no lo es. */
    public Claims parse(String token) {
        try {
            return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    public boolean isValid(String token) {
        return parse(token) != null;
    }
}
