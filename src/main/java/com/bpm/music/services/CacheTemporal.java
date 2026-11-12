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

    public CacheTemporal(long ttlMs, int maximo) {
        this.ttlMs = ttlMs;
        this.maximo = maximo;
    }

    public V obtener(K clave, Supplier<V> calcular) {
        long ahora = System.currentTimeMillis();
        Entrada<V> e = datos.get(clave);
        if (e != null && e.vence() > ahora) return e.valor();
        V v = calcular.get();
        if (datos.size() >= maximo) datos.entrySet().removeIf(x -> x.getValue().vence() <= ahora);
        if (datos.size() < maximo) datos.put(clave, new Entrada<>(v, ahora + ttlMs));
        return v;
    }

    /** Valor guardado y vigente, o null (sin calcular nada). */
    public V leer(K clave) {
        Entrada<V> e = datos.get(clave);
        return e != null && e.vence() > System.currentTimeMillis() ? e.valor() : null;
    }

    public void poner(K clave, V valor) {
        long ahora = System.currentTimeMillis();
        if (datos.size() >= maximo) datos.entrySet().removeIf(x -> x.getValue().vence() <= ahora);
        if (datos.size() < maximo) datos.put(clave, new Entrada<>(valor, ahora + ttlMs));
    }

    public void borrar(K clave) {
        datos.remove(clave);
    }

    public void limpiar() {
        datos.clear();
    }
}
