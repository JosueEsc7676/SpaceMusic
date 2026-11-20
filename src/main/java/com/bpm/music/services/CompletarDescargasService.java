package com.bpm.music.services;

import com.bpm.music.dtos.CancionDTO;
import com.bpm.music.repositories.CancionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Completa solas las canciones bajadas de YouTube (o SoundCloud), que llegan con la miniatura del video y sin letra:
 * - letra (sincronizada si existe) con todas las fuentes de {@link LetrasService};
 * - portada original en HD y álbum/año de iTunes o Deezer, solo cuando título y artista coinciden de verdad
 *   (puntaje 3) y la portada actual es la miniatura del video (no cuadrada) o no hay portada.
 * Las de Spotify (spotdl) ya traen todo y no se tocan. Cada canción se revisa una sola vez.
 */
@Service
public class CompletarDescargasService {

    private static final Logger log = LoggerFactory.getLogger(CompletarDescargasService.class);
    private static final int POR_VUELTA = 15;

    private final CancionRepository repo;
    private final BibliotecaService biblioteca;
    private final LetrasService letras;
    private final PortadaEnLineaService portadas;
    private final CancionService canciones;
    private final Path hechasArchivo;
    private final Set<Long> hechas = Collections.synchronizedSet(new HashSet<>());

    public CompletarDescargasService(CancionRepository repo, BibliotecaService biblioteca, LetrasService letras,
                                     PortadaEnLineaService portadas, CancionService canciones, ImagenService imagenes,
                                     @Value("${descargas.completar:true}") boolean activo) {
        this.repo = repo;
        this.biblioteca = biblioteca;
        this.letras = letras;
        this.portadas = portadas;
        this.canciones = canciones;
        this.hechasArchivo = imagenes.carpeta("estado").resolve("descargas-completadas.txt");
        this.activo = activo;
        try {
            if (Files.isRegularFile(hechasArchivo))
                for (String l : Files.readAllLines(hechasArchivo)) if (l.matches("\\d+")) hechas.add(Long.parseLong(l));
        } catch (Exception ignored) { }
    }

    private final boolean activo;

    /** Bajada con yt-dlp: «Descargas/Artista/Título.m4a» (spotdl usa «Descargas/Artista/Álbum/NN - Título»). */
    static boolean esDeYoutube(String ruta) {
        if (ruta == null) return false;
        String r = ruta.replace('\\', '/');
        int i = r.indexOf("/Descargas/");
        if (i < 0) return false;
        String resto = r.substring(i + "/Descargas/".length());
        return resto.split("/").length == 2 && !resto.startsWith("Directas/");
    }

    /** La portada es la miniatura del video (16:9) o no hay. */
    private boolean portadaDeVideo(Long id) {
        try {
            byte[] p = repo.findPortadaById(id).orElse(null);
            if (p == null || p.length == 0) return true;
            var img = ImageIO.read(new ByteArrayInputStream(p));
            if (img == null) return true;
            double r = (double) img.getWidth() / Math.max(1, img.getHeight());
            return r > 1.15 || r < 0.87 || Math.min(img.getWidth(), img.getHeight()) < 300;
        } catch (Exception e) {
            return false;
        }
    }

    @Scheduled(initialDelay = 3 * 60_000L, fixedDelay = 10 * 60_000L)
    public void completar() {
        if (!activo) return;
        int hechasAhora = 0;
        for (Object[] f : repo.findAllIdsYRutas()) {
            if (hechasAhora >= POR_VUELTA) break;
            Long id = ((Number) f[0]).longValue();
            if (hechas.contains(id) || !esDeYoutube((String) f[1])) continue;
            try {
                completarUna(id);
            } catch (Exception e) {
                log.debug("No se pudo completar la descarga {}: {}", id, e.getMessage());
            }
            marcar(id);
            hechasAhora++;
        }
    }

    void completarUna(Long id) throws Exception {
        CancionDTO c = biblioteca.porId(id).orElse(null);
        if (c == null) return;
        if (!c.isTieneLetra()) letras.buscarEnInternet(id, false);
        List<Map<String, Object>> ops = portadas.buscar(id, null);
        Map<String, Object> mejor = ops.stream().filter(o -> ((Number) o.get("puntaje")).intValue() >= 3).findFirst().orElse(null);
        if (mejor == null) return;
        if (portadaDeVideo(id)) portadas.aplicar(id, (String) mejor.get("url"));
        // Álbum y año si faltan (YouTube no los trae)
        Map<String, Object> datos = new HashMap<>();
        String album = Objects.toString(mejor.get("album"), "").replaceAll("(?i)\\s*-\\s*(single|ep)$", "").trim();
        if (!album.isEmpty() && (c.getAlbum() == null || c.getAlbum().isBlank() || "Single".equalsIgnoreCase(c.getAlbum())
                || c.getAlbum().equalsIgnoreCase(c.getNombre()))) datos.put("album", album);
        if (c.getAnio() == null && mejor.get("anio") instanceof Number n) datos.put("anio", n.intValue());
        if (!datos.isEmpty()) canciones.editar(id, datos);
    }

    private void marcar(Long id) {
        hechas.add(id);
        try {
            Files.createDirectories(hechasArchivo.getParent());
            Files.writeString(hechasArchivo, id + "\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) { }
    }
}
