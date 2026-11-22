package com.bpm.music.services;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Caché sencilla en memoria: guarda un valor por clave durante unos segundos. */
public class CacheTemporal<K, V> {

    private record Entrada<V>(V valor, long vence) {}

    private final Map<K, Entrada<V>> datos = new ConcurrentHashMap<>();
    private final long ttlMs;
    private final int maximo;
    private final Object[] turnos = new Object[64];
    private final java.util.concurrent.atomic.AtomicLong generacion = new java.util.concurrent.atomic.AtomicLong();

    public CacheTemporal(long ttlMs, int maximo) {
        this.ttlMs = ttlMs;
        this.maximo = maximo;
        java.util.Arrays.setAll(turnos, i -> new Object());
    }

    public V obtener(K clave, Supplier<V> calcular) {
        V existente = leer(clave);
        if (existente != null) return existente;
        synchronized (turnos[(clave.hashCode() & Integer.MAX_VALUE) % turnos.length]) {
            existente = leer(clave);
            if (existente != null) return existente;
            long version = generacion.get();
            V v = calcular.get();
            synchronized (this) { if (generacion.get() == version) poner(clave, v); }
            return v;
        }
    }

    /** Valor guardado y vigente, o null (sin calcular nada). */
    public V leer(K clave) {
        Entrada<V> e = datos.get(clave);
        return e != null && e.vence() > System.currentTimeMillis() ? e.valor() : null;
    }

    public synchronized void poner(K clave, V valor) {
        long ahora = System.currentTimeMillis();
        if (datos.size() >= maximo) datos.entrySet().removeIf(x -> x.getValue().vence() <= ahora);
        if (datos.containsKey(clave) || datos.size() < maximo) datos.put(clave, new Entrada<>(valor, ahora + ttlMs));
    }

    public synchronized void borrar(K clave) {
        generacion.incrementAndGet();
        datos.remove(clave);
    }

    public synchronized void limpiar() {
        generacion.incrementAndGet();
        datos.clear();
    }
}
