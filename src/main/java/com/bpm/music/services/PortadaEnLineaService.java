package com.bpm.music.services;

import com.bpm.music.dtos.CancionDTO;
import com.bpm.music.externo.Coincidencias;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * Busca la portada original de una canción en internet (iTunes y Deezer, gratis y sin claves) y la pone en HD.
 * Pensado para las canciones bajadas de YouTube, que traen la miniatura del video (recortada o con texto encima).
 * La persona elige entre las opciones: nunca se cambia una portada sola.
 */
@Service
public class PortadaEnLineaService {

    /** Solo se descargan imágenes de los servidores de portadas de iTunes y Deezer. */
    private static final List<String> HOSTS = List.of(".mzstatic.com", ".dzcdn.net");
    private static final int MAX_BYTES = 10 * 1024 * 1024;

    private final BibliotecaService biblioteca;
    private final CancionService canciones;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    public PortadaEnLineaService(BibliotecaService biblioteca, CancionService canciones) {
        this.biblioteca = biblioteca;
        this.canciones = canciones;
    }

    /** Título limpio para buscar: sin «| Álbum», «(Official Video)», «feat.»… */
    static String tituloLimpio(String titulo) {
        String t = titulo == null ? "" : titulo;
        int barra = t.indexOf(" | ");
        if (barra > 0) t = t.substring(0, barra);
        t = t.replaceAll("(?i)[\\(\\[][^\\)\\]]*(official|oficial|video|audio|lyric|letra|visualizer)[^\\)\\]]*[\\)\\]]", " ");
        return t.replaceAll("\\s+", " ").trim();
    }

    /** Artista principal (el primero antes de «,», «&», «feat.»…). */
    static String artistaPrincipal(String artista) {
        Set<String> a = Coincidencias.artistas(artista);
        return a.isEmpty() ? (artista == null ? "" : artista) : a.iterator().next();
    }

    /** Qué tan bien coincide una opción con la canción: 3 = mismo título y artista. */
    static int puntaje(String titulo, String artista, CancionDTO c) {
        String a = Coincidencias.base(titulo), b = Coincidencias.base(tituloLimpio(c.getNombre()));
        int p = a.equals(b) && !a.isEmpty() ? 2 : (!a.isEmpty() && !b.isEmpty() && (a.contains(b) || b.contains(a))) ? 1 : 0;
        Set<String> loc = Coincidencias.artistas(c.getArtista());
        for (String x : Coincidencias.artistas(artista))
            if (loc.stream().anyMatch(l -> l.contains(x) || x.contains(l))) { p++; break; }
        return p;
    }

    /**
     * Opciones de portada para la canción. `q` permite buscar con otro texto (por ejemplo si el título está mal).
     * Cada opción: fuente, titulo, artista, album, miniatura, url (HD), ancho, puntaje.
     */
    public List<Map<String, Object>> buscar(Long id, String q) {
        CancionDTO c = biblioteca.requerir(id);
        String consulta = q != null && !q.isBlank() ? q.trim() : (artistaPrincipal(c.getArtista()) + " " + tituloLimpio(c.getNombre())).trim();
        if (consulta.length() > 200) consulta = consulta.substring(0, 200);
        List<Map<String, Object>> r = new ArrayList<>();
        try { itunes(consulta, c, r); } catch (Exception ignored) { }
        try { deezer(consulta, c, r); } catch (Exception ignored) { }
        // Sin repetir el mismo álbum; primero las que mejor coinciden
        Map<String, Map<String, Object>> unicas = new LinkedHashMap<>();
        r.sort(Comparator.comparing((Map<String, Object> m) -> -(Integer) m.get("puntaje")));
        for (Map<String, Object> m : r) {
            String clave = Coincidencias.base(Objects.toString(m.get("album"), "")) + "|" + Coincidencias.base(Objects.toString(m.get("artista"), ""));
            unicas.putIfAbsent(clave, m);
        }
        return unicas.values().stream().limit(12).toList();
    }

    private JsonNode get(String url) throws Exception {
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "SpaceMusic")
                .timeout(Duration.ofSeconds(12)).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IllegalStateException("HTTP " + res.statusCode());
        return json.readTree(res.body());
    }

    private void itunes(String q, CancionDTO c, List<Map<String, Object>> r) throws Exception {
        JsonNode n = get("https://itunes.apple.com/search?media=music&entity=song&limit=12&term=" + URLEncoder.encode(q, StandardCharsets.UTF_8));
        for (JsonNode x : n.path("results")) {
            String chica = x.path("artworkUrl100").asText("");
            if (chica.isEmpty()) continue;
            // La misma imagen en 1400 × 1400 (iTunes la genera al tamaño pedido)
            String hd = chica.replaceAll("/\\d+x\\d+bb\\.(jpg|png|webp)$", "/1400x1400bb.jpg");
            r.add(opcion("iTunes", x.path("trackName").asText(), x.path("artistName").asText(), x.path("collectionName").asText(),
                    chica.replaceAll("/\\d+x\\d+bb\\.", "/300x300bb."), hd, 1400, c));
        }
    }

    private void deezer(String q, CancionDTO c, List<Map<String, Object>> r) throws Exception {
        JsonNode n = get("https://api.deezer.com/search?limit=12&q=" + URLEncoder.encode(q, StandardCharsets.UTF_8));
        for (JsonNode x : n.path("data")) {
            JsonNode al = x.path("album");
            String hd = al.path("cover_xl").asText("");
            if (hd.isEmpty()) continue;
            r.add(opcion("Deezer", x.path("title").asText(), x.path("artist").path("name").asText(), al.path("title").asText(),
                    al.path("cover_medium").asText(hd), hd, 1000, c));
        }
    }

    private static Map<String, Object> opcion(String fuente, String titulo, String artista, String album, String miniatura,
                                              String url, int ancho, CancionDTO c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fuente", fuente);
        m.put("titulo", titulo);
        m.put("artista", artista);
        m.put("album", album);
        m.put("miniatura", miniatura);
        m.put("url", url);
        m.put("ancho", ancho);
        m.put("puntaje", puntaje(titulo, artista, c));
        return m;
    }

    /** ¿La dirección es de un servidor de portadas de iTunes o Deezer (https)? */
    static boolean permitida(String url) {
        try {
            URI u = URI.create(url);
            String h = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
            return "https".equals(u.getScheme()) && u.getUserInfo() == null && u.getPort() == -1
                    && HOSTS.stream().anyMatch(h::endsWith);
        } catch (Exception e) {
            return false;
        }
    }

    /** Descarga la portada elegida y la pone a la canción. */
    public CancionDTO aplicar(Long id, String url) throws Exception {
        biblioteca.requerir(id);
        if (url == null || !permitida(url)) throw new IllegalArgumentException("Esa imagen no viene de iTunes ni de Deezer");
        HttpResponse<byte[]> res = http.send(HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "SpaceMusic")
                .timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        // Tras una redirección también tiene que seguir en un servidor permitido
        if (res.statusCode() != 200 || !permitida(res.uri().toString())) throw new IllegalArgumentException("No se pudo bajar la portada");
        byte[] img = res.body();
        if (img == null || img.length == 0 || img.length > MAX_BYTES) throw new IllegalArgumentException("La portada no es válida");
        return canciones.cambiarPortada(id, img);
    }
}
