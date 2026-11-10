package com.bpm.music.soporte;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Actividad reciente de las cuentas (en memoria, sin guardar contenido privado):
 * última petición, tipo de cliente, versión de la app, IP parcial y fallos de acceso.
 * Los tokens son sin estado (JWT): "sesión activa" = cuenta con actividad en los últimos minutos.
 */
@Service
public class SesionesService {

    public record Actividad(long ultima, String cliente, String version, Set<String> ips, int peticiones) {}
    public record FalloAcceso(long fecha, int estado, String endpoint, String cliente, String ip, Long usuarioId) {}
    public record FalloLogin(long fecha, String usuario, String ip) {}

    private final Map<Long, Actividad> actividad = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<FalloAcceso> fallos = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<FalloLogin> fallosLogin = new ConcurrentLinkedDeque<>();
    private static final int MAX = 300;

    void registrar(Long uid, String userAgent, String version, String ip) {
        if (uid == null) return;
        String cliente = cliente(userAgent);
        String ipp = Sanitizador.ipParcial(ip);
        actividad.compute(uid, (k, a) -> {
            Set<String> ips = a == null ? new LinkedHashSet<>() : new LinkedHashSet<>(a.ips());
            if (ips.size() < 10) ips.add(ipp);
            return new Actividad(System.currentTimeMillis(), cliente, version != null ? version : (a != null ? a.version() : null),
                    ips, a == null ? 1 : a.peticiones() + 1);
        });
    }

    void falloAcceso(int estado, String endpoint, String userAgent, String ip, Long uid) {
        fallos.addFirst(new FalloAcceso(System.currentTimeMillis(), estado, endpoint, cliente(userAgent), Sanitizador.ipParcial(ip), uid));
        while (fallos.size() > MAX) fallos.pollLast();
    }

    /** Llamado desde el inicio de sesión cuando la contraseña no coincide. */
    public void falloLogin(String usuario, String ip) {
        String u = usuario == null ? "" : usuario.trim();
        // Solo las primeras letras: no se guarda el usuario o correo completo
        String parcial = u.length() <= 2 ? "**" : u.substring(0, 2) + "***";
        fallosLogin.addFirst(new FalloLogin(System.currentTimeMillis(), parcial, Sanitizador.ipParcial(ip)));
        while (fallosLogin.size() > MAX) fallosLogin.pollLast();
    }

    /** Sesiones con actividad en los últimos {@code minutos}. */
    public Map<Long, Actividad> activas(int minutos) {
        long desde = System.currentTimeMillis() - minutos * 60_000L;
        Map<Long, Actividad> r = new HashMap<>();
        actividad.forEach((k, v) -> { if (v.ultima() >= desde) r.put(k, v); });
        return r;
    }

    public Optional<Actividad> de(Long uid) {
        return Optional.ofNullable(actividad.get(uid));
    }

    public List<FalloAcceso> fallos() { return new ArrayList<>(fallos); }

    public List<FalloLogin> fallosLogin() { return new ArrayList<>(fallosLogin); }

    public long fallosLoginDesde(long desde) {
        return fallosLogin.stream().filter(f -> f.fecha() >= desde).count();
    }

    /**
     * Criterios simples y explicables de actividad sospechosa:
     * muchas IPs distintas en una misma cuenta o muchos inicios de sesión fallidos desde una IP en 1 hora.
     */
    public List<Map<String, Object>> sospechosas() {
        List<Map<String, Object>> r = new ArrayList<>();
        long hora = System.currentTimeMillis() - 3_600_000L;
        actividad.forEach((uid, a) -> {
            if (a.ultima() >= hora && a.ips().size() >= 4)
                r.add(Map.of("usuarioId", uid, "motivo", "Actividad desde " + a.ips().size() + " redes distintas", "desde", Instant.ofEpochMilli(a.ultima()).toString()));
        });
        Map<String, Long> porIp = new HashMap<>();
        for (FalloLogin f : fallosLogin) if (f.fecha() >= hora) porIp.merge(f.ip(), 1L, Long::sum);
        porIp.forEach((ip, n) -> { if (n >= 10) r.add(Map.of("ip", ip, "motivo", n + " inicios de sesión fallidos en una hora")); });
        return r;
    }

    static String cliente(String ua) {
        if (ua == null) return "Desconocido";
        if (ua.contains("SpaceMusicApp")) return "App Android";
        if (ua.contains("SpaceMusicTV") || ua.contains("AFT") || ua.contains("BRAVIA") || ua.contains("Android TV")) return "TV";
        if (ua.contains("iPhone") || ua.contains("iPad")) return "iPhone / iPad (web)";
        if (ua.contains("Android")) return "Android (web)";
        if (ua.contains("Macintosh")) return "Mac (web)";
        if (ua.contains("Windows")) return "Windows (web)";
        if (ua.contains("Linux")) return "Linux (web)";
        return "Otro";
    }
}
