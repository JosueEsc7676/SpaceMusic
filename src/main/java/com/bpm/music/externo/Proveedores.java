package com.bpm.music.externo;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Estado de los proveedores externos (YouTube Music, LRCLIB, MusicBrainz, Cover Art Archive, Ollama):
 * llamadas, fallos, último error y pausa automática tras fallos seguidos. Lo muestra Administración.
 */
@Component
public class Proveedores {

    public static final class Estado {
        final String nombre;
        final String descripcion;
        final AtomicLong llamadas = new AtomicLong(), fallos = new AtomicLong(), seguidos = new AtomicLong();
        volatile String ultimoError;
        volatile Instant ultimoFallo, ultimoExito, pausadoHasta;

        Estado(String nombre, String descripcion) { this.nombre = nombre; this.descripcion = descripcion; }
    }

    private final Map<String, Estado> estados = new ConcurrentHashMap<>();

    public Estado registrar(String clave, String nombre, String descripcion) {
        return estados.computeIfAbsent(clave, k -> new Estado(nombre, descripcion));
    }

    /** ¿Se puede llamar ahora? (no, si está en pausa por fallos seguidos). */
    public boolean permitido(String clave) {
        Estado e = estados.get(clave);
        return e == null || e.pausadoHasta == null || Instant.now().isAfter(e.pausadoHasta);
    }

    public void exito(String clave) {
        Estado e = estados.get(clave);
        if (e == null) return;
        e.llamadas.incrementAndGet();
        e.seguidos.set(0);
        e.ultimoExito = Instant.now();
        e.pausadoHasta = null;
    }

    /** Tras 3 fallos seguidos se pausa 5 minutos (10 tras 6…, máximo 1 hora). */
    public void fallo(String clave, String error) {
        Estado e = estados.get(clave);
        if (e == null) return;
        e.llamadas.incrementAndGet();
        e.fallos.incrementAndGet();
        long n = e.seguidos.incrementAndGet();
        e.ultimoError = error == null ? "Error desconocido" : (error.length() > 200 ? error.substring(0, 200) : error);
        e.ultimoFallo = Instant.now();
        if (n >= 3) e.pausadoHasta = Instant.now().plusSeconds(Math.min(3600, 300L * (n / 3)));
    }

    public List<Map<String, Object>> resumen() {
        List<Map<String, Object>> r = new ArrayList<>();
        estados.forEach((k, e) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("clave", k);
            m.put("nombre", e.nombre);
            m.put("descripcion", e.descripcion);
            m.put("llamadas", e.llamadas.get());
            m.put("fallos", e.fallos.get());
            m.put("ultimoError", e.ultimoError);
            m.put("ultimoFallo", e.ultimoFallo);
            m.put("ultimoExito", e.ultimoExito);
            m.put("pausadoHasta", e.pausadoHasta != null && Instant.now().isBefore(e.pausadoHasta) ? e.pausadoHasta : null);
            r.add(m);
        });
        r.sort(Comparator.comparing(m -> (String) m.get("nombre")));
        return r;
    }
}
