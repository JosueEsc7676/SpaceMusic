package com.bpm.music.services;

import com.bpm.music.dtos.CancionDTO;
import com.bpm.music.repositories.CancionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Letras de canciones: lectura, guardado y búsqueda automática en LRCLIB. */
@Service
public class LetrasService {

    private static final Logger log = LoggerFactory.getLogger(LetrasService.class);
    private static final String LRCLIB = "https://lrclib.net/api";

    private final CancionRepository repo;
    private final BibliotecaService biblioteca;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    /** Canciones ya buscadas sin éxito (no repetir la búsqueda en cada reproducción). */
    private final Map<Long, Long> sinResultado = new ConcurrentHashMap<>();

    public LetrasService(CancionRepository repo, BibliotecaService biblioteca) {
        this.repo = repo;
        this.biblioteca = biblioteca;
    }

    public Map<String, Object> obtener(Long id) {
        biblioteca.requerir(id);
        List<Object[]> filas = repo.findLetrasById(id);
        String letra = "", lrc = "";
        if (!filas.isEmpty()) {
            Object[] f = filas.get(0);
            letra = f[0] == null ? "" : f[0].toString();
            lrc = f[1] == null ? "" : f[1].toString();
        }
        return respuesta(letra, lrc, null);
    }

    public Map<String, Object> guardar(Long id, String texto) {
        biblioteca.requerir(id);
        if (texto == null) texto = "";
        texto = texto.replace("\r\n", "\n").trim();
        if (texto.startsWith("﻿")) texto = texto.substring(1);
        if (texto.length() > 200_000) throw new IllegalArgumentException("La letra es demasiado larga");
        boolean esLrc = MetadataService.LRC_TIMESTAMP.matcher(texto).find();
        String letra = esLrc ? textoPlanoDeLrc(texto) : texto;
        String lrc = esLrc ? texto : "";
        repo.actualizarLetras(id, letra, lrc);
        sinResultado.remove(id);
        biblioteca.invalidar();
        return respuesta(letra, lrc, null);
    }

    public void borrar(Long id) {
        biblioteca.requerir(id);
        repo.actualizarLetras(id, "", "");
        biblioteca.invalidar();
    }

    /**
     * Busca la letra en LRCLIB por artista, título y duración.
     * Si la encuentra la guarda y la devuelve.
     */
    public Map<String, Object> buscarEnInternet(Long id, boolean forzar) {
        CancionDTO c = biblioteca.requerir(id);
        Long antes = sinResultado.get(id);
        if (!forzar && antes != null && System.currentTimeMillis() - antes < 24 * 3600_000L) {
            return Map.of("encontrada", false, "message", "No se encontró la letra en línea");
        }
        try {
            JsonNode hit = buscarExacta(c);
            if (hit == null) hit = buscarAproximada(c);
            if (hit == null) {
                sinResultado.put(id, System.currentTimeMillis());
                return Map.of("encontrada", false, "message", "No se encontró la letra en línea");
            }
            if (hit.path("instrumental").asBoolean(false)) {
                return Map.of("encontrada", false, "instrumental", true, "message", "Es una canción instrumental");
            }
            String lrc = hit.path("syncedLyrics").asText("");
            String plano = hit.path("plainLyrics").asText("");
            if (lrc.isBlank() && plano.isBlank()) {
                sinResultado.put(id, System.currentTimeMillis());
                return Map.of("encontrada", false, "message", "No se encontró la letra en línea");
            }
            repo.actualizarLetras(id, plano.isBlank() ? textoPlanoDeLrc(lrc) : plano.trim(), lrc.trim());
            biblioteca.invalidar();
            Map<String, Object> r = new HashMap<>(respuesta(plano, lrc, "LRCLIB"));
            r.put("encontrada", true);
            return r;
        } catch (Exception e) {
            log.debug("Error buscando letra de {}: {}", id, e.getMessage());
            return Map.of("encontrada", false, "message", "No se pudo conectar con el servicio de letras");
        }
    }

    private JsonNode buscarExacta(CancionDTO c) throws Exception {
        StringBuilder url = new StringBuilder(LRCLIB + "/get?")
                .append("artist_name=").append(enc(c.getArtista()))
                .append("&track_name=").append(enc(limpiarTitulo(c.getNombre())));
        if (c.getAlbum() != null && !"Single".equals(c.getAlbum())) url.append("&album_name=").append(enc(c.getAlbum()));
        if (c.getDuracionSegundos() > 0) url.append("&duration=").append(c.getDuracionSegundos());
        HttpResponse<String> res = get(url.toString());
        if (res.statusCode() != 200) return null;
        return mapper.readTree(res.body());
    }

    private JsonNode buscarAproximada(CancionDTO c) throws Exception {
        String url = LRCLIB + "/search?track_name=" + enc(limpiarTitulo(c.getNombre()))
                + "&artist_name=" + enc(c.getArtista());
        HttpResponse<String> res = get(url);
        if (res.statusCode() != 200) return null;
        JsonNode arr = mapper.readTree(res.body());
        if (!arr.isArray()) return null;
        JsonNode mejor = null;
        int mejorDif = Integer.MAX_VALUE;
        for (JsonNode n : arr) {
            boolean tieneLetra = !n.path("syncedLyrics").asText("").isBlank() || !n.path("plainLyrics").asText("").isBlank();
            if (!tieneLetra) continue;
            int dif = c.getDuracionSegundos() > 0 ? Math.abs(n.path("duration").asInt(0) - c.getDuracionSegundos()) : 0;
            boolean sincronizada = !n.path("syncedLyrics").asText("").isBlank();
            if (!sincronizada) dif += 5; // preferir las sincronizadas
            if (dif < mejorDif) { mejorDif = dif; mejor = n; }
        }
        // Si la duración difiere mucho probablemente es otra versión
        return (mejor != null && (c.getDuracionSegundos() == 0 || mejorDif <= 15)) ? mejor : null;
    }

    private HttpResponse<String> get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "SpaceMusic/2.0 (servidor personal de musica)")
                .GET().build();
        return http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    /** Quita "(Remastered 2011)", "[Official Video]", "- Live" etc. del título. */
    private static String limpiarTitulo(String t) {
        if (t == null) return "";
        String r = t.replaceAll("(?i)\\s*[(\\[][^)\\]]*(remaster|official|video|audio|lyric|live|version|edit|mono|stereo)[^)\\]]*[)\\]]", "");
        r = r.replaceAll("(?i)\\s+-\\s+(\\d{4}\\s+)?remaster(ed)?.*$", "");
        return r.trim().isEmpty() ? t : r.trim();
    }

    private static String textoPlanoDeLrc(String lrc) {
        StringBuilder sb = new StringBuilder();
        for (String linea : lrc.split("\n")) {
            String l = linea.replaceAll("\\[[^\\]]*]", "")
                    .replaceAll("<\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?>", "") // tiempos por palabra
                    .replaceAll("\\s+", " ").trim();
            if (!l.isEmpty()) sb.append(l).append('\n');
        }
        return sb.toString().trim();
    }

    private static Map<String, Object> respuesta(String letra, String lrc, String fuente) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("letra", letra == null ? "" : letra);
        m.put("letraSincronizada", lrc == null ? "" : lrc);
        if (fuente != null) m.put("fuente", fuente);
        return m;
    }
}
