package com.bpm.music.services;

import com.bpm.music.dtos.CancionDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Versión ligera de cada canción (AAC 128 kbps, unos 1 MB por minuto) para datos móviles y descargas que ocupan menos.
 * Se prepara en segundo plano con ffmpeg a la menor prioridad (nice 19), una a la vez; mientras no esté lista
 * se entrega el archivo original. Las canciones que ya pesan poco (≤ 160 kbps) no se convierten: el original sirve.
 * Dura exactamente lo mismo que el original (la app compara duraciones para detectar copias equivocadas).
 */
@Service
public class AudioLigeroService {

    private static final Logger log = LoggerFactory.getLogger(AudioLigeroService.class);
    static final int KBPS_LIGERO = 128;
    private static final int KBPS_MAXIMO_SIN_CONVERTIR = 160;

    private final Path carpeta;
    private final String ffmpeg;
    private final boolean activo;
    private final CancionService canciones;
    private final BibliotecaService biblioteca;
    private final ExecutorService cola = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "audio-ligero");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final Set<Long> pendientes = ConcurrentHashMap.newKeySet();
    /** Ya revisadas por la tarea de fondo (listas o sin necesidad): no se vuelven a consultar. */
    private final Set<Long> revisadas = ConcurrentHashMap.newKeySet();
    private volatile Boolean ffmpegDisponible;

    public AudioLigeroService(@Value("${music.light-path:./ligero}") String carpeta,
                              @Value("${organizador.ffmpeg:ffmpeg}") String ffmpeg,
                              @Value("${music.audio-ligero:true}") boolean activo,
                              CancionService canciones, BibliotecaService biblioteca) {
        this.carpeta = Path.of(carpeta).toAbsolutePath().normalize();
        this.ffmpeg = ffmpeg;
        this.activo = activo;
        this.canciones = canciones;
        this.biblioteca = biblioteca;
    }

    /** ¿Hace falta una versión ligera? (las que ya pesan poco se entregan tal cual) */
    public static boolean necesita(long bytes, int segundos) {
        if (segundos <= 0) return bytes > 6L * 1024 * 1024;
        long kbps = bytes * 8 / 1000 / segundos;
        return kbps > KBPS_MAXIMO_SIN_CONVERTIR;
    }

    private Path destino(long id, File original) {
        return carpeta.resolve(id + "_" + original.length() + "_" + original.lastModified() + ".m4a");
    }

    /**
     * Archivo para entregar en calidad ligera: la versión ligera si ya existe, si no el original
     * (y se pide prepararla en segundo plano).
     */
    public File archivo(long id) {
        File original = canciones.archivo(id);
        revisadas.remove(id); // por si el archivo cambió
        if (original == null || !activo) return original;
        int dur = biblioteca.porId(id).map(CancionDTO::getDuracionSegundos).orElse(0);
        if (!necesita(original.length(), dur)) return original;
        Path d = destino(id, original);
        if (Files.isRegularFile(d)) return d.toFile();
        encolar(id);
        return original;
    }

    /** ¿Este archivo es una versión ligera (y no el original que se entrega mientras se prepara)? */
    public boolean esLigera(File f) {
        return f != null && f.toPath().toAbsolutePath().normalize().startsWith(carpeta);
    }

    private boolean hayFfmpeg() {
        if (ffmpegDisponible == null) {
            try {
                Process p = new ProcessBuilder(ffmpeg, "-version").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                ffmpegDisponible = p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
            } catch (Exception e) {
                ffmpegDisponible = false;
            }
        }
        return ffmpegDisponible;
    }

    private void encolar(long id) {
        if (!activo || !pendientes.add(id)) return;
        cola.submit(() -> {
            try { convertir(id); } finally { pendientes.remove(id); }
        });
    }

    /** Convierte una canción. Devuelve true si quedó lista (o no hacía falta). */
    boolean convertir(long id) {
        File original = canciones.archivo(id);
        if (original == null || !original.canRead() || !hayFfmpeg()) return false;
        Path d = destino(id, original);
        if (Files.isRegularFile(d)) return true;
        try {
            Files.createDirectories(carpeta);
            Path tmp = carpeta.resolve(id + ".tmp.m4a");
            var cmd = new java.util.ArrayList<String>();
            if (new File("/usr/bin/nice").canExecute()) { cmd.add("/usr/bin/nice"); cmd.add("-n"); cmd.add("19"); }
            cmd.addAll(java.util.List.of(ffmpeg, "-nostdin", "-v", "error", "-y", "-i", original.getAbsolutePath(),
                    "-map", "0:a:0", "-vn", "-c:a", "aac", "-b:a", KBPS_LIGERO + "k", "-ac", "2",
                    "-map_metadata", "-1", "-movflags", "+faststart", tmp.toString()));
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!p.waitFor(10, TimeUnit.MINUTES)) { p.destroyForcibly(); Files.deleteIfExists(tmp); return false; }
            if (p.exitValue() != 0 || !Files.isRegularFile(tmp) || Files.size(tmp) < 10_000) { Files.deleteIfExists(tmp); return false; }
            Files.move(tmp, d, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            // Versiones viejas de la misma canción (el archivo original cambió)
            try (var viejas = Files.list(carpeta)) {
                viejas.filter(x -> x.getFileName().toString().startsWith(id + "_") && !x.equals(d)).forEach(x -> x.toFile().delete());
            }
            return true;
        } catch (Exception e) {
            log.debug("No se pudo preparar la versión ligera de {}: {}", id, e.getMessage());
            return false;
        }
    }

    /** Poco a poco, en segundo plano: hasta 4 canciones cada 3 minutos (unas 1900 al día). */
    @Scheduled(initialDelay = 5, fixedDelay = 3, timeUnit = TimeUnit.MINUTES)
    public void prepararPocoAPoco() {
        if (!activo || !hayFfmpeg() || !pendientes.isEmpty()) return;
        int n = 0;
        for (CancionDTO c : biblioteca.todas()) {
            if (n >= 4) break;
            if (revisadas.contains(c.getId())) continue;
            File f;
            try { f = canciones.archivo(c.getId()); } catch (RuntimeException e) { continue; }
            if (f == null || !necesita(f.length(), c.getDuracionSegundos()) || Files.isRegularFile(destino(c.getId(), f))) {
                revisadas.add(c.getId());
                continue;
            }
            encolar(c.getId());
            n++;
        }
    }

    /** Para el panel: cuántas hay listas y cuánto ocupan. */
    public java.util.Map<String, Object> estado() {
        long n = 0, bytes = 0;
        if (Files.isDirectory(carpeta)) {
            try (var s = Files.list(carpeta)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    if (p.toString().endsWith(".m4a") && !p.toString().endsWith(".tmp.m4a")) { n++; bytes += p.toFile().length(); }
                }
            } catch (Exception ignored) { }
        }
        return java.util.Map.of("listas", n, "megas", bytes / 1024 / 1024, "activo", activo && hayFfmpeg());
    }
}
