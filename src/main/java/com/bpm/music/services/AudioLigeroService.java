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
import java.util.concurrent.ScheduledExecutorService;
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
    private final ScheduledExecutorService cola = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "audio-ligero");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final Set<Long> pendientes = ConcurrentHashMap.newKeySet();
    /** Ya revisadas por la tarea de fondo (listas o sin necesidad): no se vuelven a consultar. */
    private final Set<Long> revisadas = ConcurrentHashMap.newKeySet();
    private volatile Boolean ffmpegDisponible;
    private com.bpm.music.soporte.MonitorService monitor;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setMonitor(com.bpm.music.soporte.MonitorService monitor) { this.monitor = monitor; }

    /** CPU del sistema (0 a 1) por encima de la cual la preparación espera (las pruebas lo suben para no depender de la carga). */
    volatile double cpuMaxima = 0.70;

    private boolean ocupado() {
        if (monitor != null && monitor.audiosActivos() > 0) return true;
        var so = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        return so instanceof com.sun.management.OperatingSystemMXBean cpu && cpu.getCpuLoad() > cpuMaxima;
    }

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

    private Path destinoRapido(long id, File original) {
        return carpeta.resolve("inicio").resolve(id + "_" + original.length() + "_" + original.lastModified()
                + "." + MetadataService.extension(original.getName()).toLowerCase(java.util.Locale.ROOT));
    }

    /** Misma pista y calidad, sin imagen adjunta ni etiquetas grandes delante del sonido. */
    public File archivoRapido(long id) {
        return rapidoOOriginal(id, canciones.archivo(id));
    }

    private File rapidoOOriginal(long id, File original) {
        if (original == null || !activo) return original;
        String ext = MetadataService.extension(original.getName()).toLowerCase(java.util.Locale.ROOT);
        if (!Set.of("mp3", "m4a", "mp4").contains(ext)) return original;
        Path d = destinoRapido(id, original);
        if (Files.isRegularFile(d)) return d.toFile();
        encolar(id); // solo encola: nunca espera a ffmpeg en la petición de reproducción
        return original;
    }

    /**
     * Archivo para entregar en calidad ligera: la versión ligera si ya existe, si no el original
     * (y se pide prepararla en segundo plano).
     */
    public File archivo(long id) {
        File original = canciones.archivo(id);
        if (original == null || !activo) return original;
        int dur = biblioteca.porId(id).map(CancionDTO::getDuracionSegundos).orElse(0);
        if (!necesita(original.length(), dur)) return rapidoOOriginal(id, original);
        Path d = destino(id, original);
        if (Files.isRegularFile(d)) return d.toFile();
        encolar(id);
        return rapidoOOriginal(id, original);
    }

    /** ¿Este archivo es una versión ligera (y no el original que se entrega mientras se prepara)? */
    public boolean esLigera(File f) {
        return f != null && carpeta.equals(f.toPath().toAbsolutePath().normalize().getParent());
    }

    public boolean inicioPendiente(File f) {
        if (!activo || f == null || esLigera(f)) return false;
        return !carpeta.resolve("inicio").equals(f.toPath().toAbsolutePath().normalize().getParent())
                && Set.of("mp3", "m4a", "mp4").contains(MetadataService.extension(f.getName()).toLowerCase(java.util.Locale.ROOT));
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

    private synchronized void encolar(long id) {
        if (!activo || pendientes.size() >= 8 || !pendientes.add(id)) return;
        cola.execute(() -> procesarPendiente(id));
    }

    private void procesarPendiente(long id) {
        // Una transferencia activa aplaza el trabajo: no perder la canción que se acaba de solicitar.
        if (ocupado()) {
            cola.schedule(() -> procesarPendiente(id), 2, TimeUnit.SECONDS);
            return;
        }
        try { convertir(id); } finally { pendientes.remove(id); }
    }

    /** Convierte una canción. Devuelve true si quedó lista (o no hacía falta). */
    boolean convertir(long id) {
        File original = canciones.archivo(id);
        if (original == null || !original.canRead() || !hayFfmpeg()) return false;
        prepararOriginal(id, original);
        int duracion = biblioteca.porId(id).map(CancionDTO::getDuracionSegundos).orElse(0);
        if (!necesita(original.length(), duracion)) return true;
        Path d = destino(id, original);
        if (Files.isRegularFile(d)) return true;
        try {
            Files.createDirectories(carpeta);
            Path tmp = carpeta.resolve(id + ".tmp.m4a");
            var cmd = new java.util.ArrayList<String>();
            if (new File("/usr/bin/nice").canExecute()) { cmd.add("/usr/bin/nice"); cmd.add("-n"); cmd.add("19"); }
            cmd.addAll(java.util.List.of(ffmpeg, "-nostdin", "-v", "error", "-y", "-i", original.getAbsolutePath(),
                    "-threads", "1", "-map", "0:a:0", "-vn", "-c:a", "aac", "-b:a", KBPS_LIGERO + "k", "-ac", "2",
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

    /** Copia del flujo comprimido, sin recodificar ni modificar el archivo de la biblioteca. */
    boolean prepararOriginal(long id, File original) {
        String ext = MetadataService.extension(original.getName()).toLowerCase(java.util.Locale.ROOT);
        if (!Set.of("mp3", "m4a", "mp4").contains(ext)) return false;
        Path d = destinoRapido(id, original);
        if (Files.isRegularFile(d)) return true;
        Path tmp = d.resolveSibling("parcial-" + d.getFileName());
        Process p = null;
        try {
            Files.createDirectories(d.getParent());
            if (Files.getFileStore(d.getParent()).getUsableSpace() < Math.max(200L * 1024 * 1024, original.length() * 2)) return false;
            var cmd = new java.util.ArrayList<String>();
            if (new File("/usr/bin/nice").canExecute()) cmd.addAll(java.util.List.of("/usr/bin/nice", "-n", "19"));
            cmd.addAll(java.util.List.of(ffmpeg, "-nostdin", "-v", "error", "-y", "-i", original.getAbsolutePath(),
                    "-map", "0:a:0", "-vn", "-c:a", "copy", "-map_metadata", "-1", "-map_chapters", "-1"));
            if (ext.equals("mp3")) cmd.addAll(java.util.List.of("-id3v2_version", "0", "-write_id3v1", "0"));
            else cmd.addAll(java.util.List.of("-movflags", "+faststart"));
            cmd.add(tmp.toString());
            p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!p.waitFor(90, TimeUnit.SECONDS)) { p.destroyForcibly(); return false; }
            if (p.exitValue() != 0 || !Files.isRegularFile(tmp) || Files.size(tmp) < 1024) return false;
            Files.move(tmp, d, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (Exception e) {
            log.debug("No se pudo preparar el inicio rápido de {}: {}", id, e.getMessage());
            return false;
        } finally {
            if (p != null && p.isAlive()) p.destroyForcibly();
            try { Files.deleteIfExists(tmp); } catch (Exception ignored) { }
        }
    }

    /** Poco a poco: hasta 4 canciones por minuto, cediendo ante reproducción o CPU alta. */
    @Scheduled(initialDelay = 1, fixedDelay = 1, timeUnit = TimeUnit.MINUTES)
    public void prepararPocoAPoco() {
        if (!activo || ocupado() || !hayFfmpeg() || !pendientes.isEmpty()) return;
        int n = 0;
        for (CancionDTO c : biblioteca.todas()) {
            if (n >= 4) break;
            if (revisadas.contains(c.getId())) continue;
            File f;
            try { f = canciones.archivo(c.getId()); } catch (RuntimeException e) { continue; }
            boolean rapidaLista = f != null && (!Set.of("mp3", "m4a", "mp4").contains(MetadataService.extension(f.getName()).toLowerCase(java.util.Locale.ROOT))
                    || Files.isRegularFile(destinoRapido(c.getId(), f)));
            if (f == null || (rapidaLista && (!necesita(f.length(), c.getDuracionSegundos()) || Files.isRegularFile(destino(c.getId(), f))))) {
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
        return java.util.Map.of("listas", n, "megas", bytes / 1024 / 1024, "activo", activo && hayFfmpeg(), "pendientes", pendientes.size());
    }
}
