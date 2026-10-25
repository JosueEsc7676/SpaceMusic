package com.bpm.music.security;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limita los intentos fallidos de inicio de sesión para frenar ataques
 * de fuerza bruta: máximo 8 fallos cada 15 minutos por IP + usuario.
 */
@Component
public class LoginRateLimiter {

    private static final int MAX_FALLOS = 8;
    private static final long VENTANA_MS = 15 * 60 * 1000L;

    private final Map<String, Deque<Long>> fallos = new ConcurrentHashMap<>();

    private String clave(String ip, String usuario) {
        return ip + "|" + (usuario == null ? "" : usuario.trim().toLowerCase());
    }

    /** Segundos que faltan para poder reintentar, o 0 si está permitido. */
    public long segundosBloqueado(String ip, String usuario) {
        Deque<Long> lista = fallos.get(clave(ip, usuario));
        if (lista == null) return 0;
        synchronized (lista) {
            limpiar(lista);
            if (lista.size() < MAX_FALLOS) return 0;
            long espera = (lista.peekFirst() + VENTANA_MS - System.currentTimeMillis()) / 1000;
            return Math.max(1, espera);
        }
    }

    public void registrarFallo(String ip, String usuario) {
        Deque<Long> lista = fallos.computeIfAbsent(clave(ip, usuario), k -> new ArrayDeque<>());
        synchronized (lista) {
            limpiar(lista);
            lista.addLast(System.currentTimeMillis());
        }
    }

    public void registrarExito(String ip, String usuario) {
        fallos.remove(clave(ip, usuario));
    }

    private void limpiar(Deque<Long> lista) {
        long limite = System.currentTimeMillis() - VENTANA_MS;
        while (!lista.isEmpty() && lista.peekFirst() < limite) lista.pollFirst();
    }

    @Scheduled(fixedDelay = 30 * 60 * 1000L)
    public void purgar() {
        fallos.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                limpiar(e.getValue());
                return e.getValue().isEmpty();
            }
        });
    }
}
