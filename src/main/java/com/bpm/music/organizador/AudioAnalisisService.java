package com.bpm.music.organizador;

import com.bpm.music.dtos.CancionDTO;
import com.bpm.music.repositories.CancionRepository;
import com.bpm.music.services.BibliotecaService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Analiza el sonido de cada canción con ffmpeg (filtro ebur128): sonoridad integrada (LUFS) y rango
 * dinámico (LRA) de 90 segundos desde el segundo 30. Con eso estima una "energía" de 0 a 1.
 * Corre con prioridad baja (nice 19) y solo dentro del organizador. El archivo va como argumento
 * separado (sin intérprete de comandos) y viene de la base de datos, no del usuario.
 */
@Service
public class AudioAnalisisService {

    private static final Pattern I = Pattern.compile("I:\\s+(-?[\\d.]+) LUFS");
    private static final Pattern LRA = Pattern.compile("LRA:\\s+([\\d.]+) LU");
    public static final String METODO = "ffmpeg ebur128 (90 s desde 0:30): sonoridad y dinámica";

    private final String ffmpeg;
    private final BibliotecaService biblioteca;
    private final CancionRepository canciones;
    private final CaracteristicaAudioRepository repo;

    public AudioAnalisisService(@Value("${organizador.ffmpeg:ffmpeg}") String ffmpeg, BibliotecaService biblioteca,
                                CancionRepository canciones, CaracteristicaAudioRepository repo) {
        this.ffmpeg = ffmpeg;
        this.biblioteca = biblioteca;
        this.canciones = canciones;
        this.repo = repo;
    }

    /** Energía estimada (0 a 1) a partir de la sonoridad y la dinámica. */
    static double energia(double lufs, double lra) {
        double fuerte = Math.max(0, Math.min(1, (lufs + 20) / 13.0));   // -20 LUFS → 0, -7 LUFS → 1
        double compacta = Math.max(0, Math.min(1, 1 - lra / 16.0));      // poca dinámica suele ser más intensa
        return Math.round((0.75 * fuerte + 0.25 * compacta) * 1000) / 1000.0;
    }

    /** Analiza hasta {@code n} canciones sin análisis (o cuyo archivo cambió). */
    public int lote(int n, BooleanSupplier seguir) {
        Map<Long, CaracteristicaAudio> hechas = new HashMap<>();
        repo.findAll().forEach(c -> hechas.put(c.getCancionId(), c));
        int procesadas = 0;
        for (CancionDTO c : biblioteca.todas()) {
            if (procesadas >= n || !seguir.getAsBoolean()) break;
            File f = archivo(c.getId());
            String huella = f == null ? "sin-archivo" : f.length() + "-" + f.lastModified();
            CaracteristicaAudio x = hechas.get(c.getId());
            if (x != null && huella.equals(x.getHuellaArchivo())) continue;
            if (x == null) { x = new CaracteristicaAudio(); x.setCancionId(c.getId()); }
            x.setHuellaArchivo(huella);
            x.setMetodo(METODO);
            x.setFecha(LocalDateTime.now());
            if (f == null) { x.setError("No se encontró el archivo"); x.setEnergia(null); repo.save(x); procesadas++; continue; }
            try {
                double[] r = medir(f, c.getDuracionSegundos());
                x.setLufs(r[0]);
                x.setLra(r[1]);
                x.setEnergia(energia(r[0], r[1]));
                x.setError(null);
            } catch (Exception e) {
                x.setError(e.getMessage() == null ? "Error al analizar" : e.getMessage().substring(0, Math.min(190, e.getMessage().length())));
            }
            repo.save(x);
            procesadas++;
        }
        if (procesadas > 0) cache.limpiar();
        return procesadas;
    }

    private File archivo(Long id) {
        String ruta = canciones.findRutaById(id).orElse(null);
        if (ruta == null) return null;
        File f = new File(ruta);
        return f.isFile() ? f : null;
    }

    double[] medir(File f, int duracion) throws Exception {
        int desde = duracion > 150 ? 30 : 0;
        List<String> cmd = new ArrayList<>();
        if (new File("/usr/bin/nice").exists()) { cmd.add("nice"); cmd.add("-n"); cmd.add("19"); }
        cmd.addAll(List.of(ffmpeg, "-nostats", "-hide_banner", "-ss", String.valueOf(desde), "-t", "90",
                "-i", f.getAbsolutePath(), "-vn", "-af", "ebur128=framelog=quiet", "-f", "null", "-"));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String salida;
        try (InputStream is = p.getInputStream()) { salida = new String(is.readNBytes(200_000), StandardCharsets.UTF_8); }
        if (!p.waitFor(60, TimeUnit.SECONDS)) { p.destroyForcibly(); throw new IllegalStateException("ffmpeg tardó demasiado"); }
        Matcher i = I.matcher(salida), l = LRA.matcher(salida);
        Double lufs = null, lra = null;
        while (i.find()) lufs = Double.parseDouble(i.group(1)); // el último es el resumen
        while (l.find()) lra = Double.parseDouble(l.group(1));
        if (lufs == null || lra == null || lufs < -70) throw new IllegalStateException("ffmpeg no devolvió la sonoridad");
        return new double[]{lufs, lra};
    }

    /** Energía de cada canción analizada (cancionId → 0..1). */
    private final com.bpm.music.services.CacheTemporal<String, Map<Long, Double>> cache = new com.bpm.music.services.CacheTemporal<>(5 * 60_000, 1);

    public Map<Long, Double> energias() {
        return cache.obtener("e", () -> {
            Map<Long, Double> r = new HashMap<>();
            for (CaracteristicaAudio c : repo.findAll()) if (c.getEnergia() != null) r.put(c.getCancionId(), c.getEnergia());
            return r;
        });
    }

    public Map<String, Object> resumen() {
        return Map.of("analizadas", repo.countByEnergiaIsNotNull(), "total", biblioteca.total(), "metodo", METODO);
    }
}
