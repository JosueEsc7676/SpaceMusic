package com.bpm.music.services;

import com.bpm.music.dtos.CancionDTO;
import com.bpm.music.models.ArtistaGenero;
import com.bpm.music.repositories.ArtistaGeneroRepository;
import com.bpm.music.repositories.CancionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Géneros por artista. Las canciones descargadas no traen género en sus etiquetas, así que
 * se consultan en MusicBrainz (base de datos musical libre) una vez por artista y se guardan.
 * Sirve para mostrar géneros, armar la radio con canciones parecidas y calcular las "vibras".
 */
@Service
public class GeneroService {

    private static final Logger log = LoggerFactory.getLogger(GeneroService.class);
    private static final String AGENTE = "SpaceMusic/2.4 ( https://spacemusic.tail8ccfdf.ts.net )";

    private final ArtistaGeneroRepository repo;
    private final CancionRepository canciones;
    private final BibliotecaService biblioteca;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    /** clave de artista → géneros (minúsculas, por relevancia). */
    private final Map<String, List<String>> cache = new ConcurrentHashMap<>();

    public GeneroService(ArtistaGeneroRepository repo, CancionRepository canciones, BibliotecaService biblioteca) {
        this.repo = repo;
        this.canciones = canciones;
        this.biblioteca = biblioteca;
    }

    // ═══════════════════ Consulta ═══════════════════

    /** Géneros del artista (vacío si no se conocen). */
    public List<String> generosDe(String artista) {
        String k = clave(artista);
        if (k.isEmpty()) return List.of();
        return cache.computeIfAbsent(k, x -> repo.findByClave(x)
                .map(a -> a.getGeneros() == null || a.getGeneros().isBlank() ? List.<String>of() : List.of(a.getGeneros().split(",")))
                .orElse(List.of()));
    }

    static String clave(String artista) {
        return BibliotecaService.normalizar(principal(artista));
    }

    /** Artista principal: "Bad Bunny, ABRA" → "Bad Bunny"; "Drake feat. X" → "Drake". */
    static String principal(String artista) {
        String a = MetadataService.primerArtista(artista == null ? "" : artista);
        return a.split("(?i)\\s*(,|;|&| feat\\.? | ft\\.? | featuring | x | y | with )\\s*")[0].trim();
    }

    // ═══════════════════ Enriquecer la biblioteca ═══════════════════

    /** Cada 30 minutos: busca el género de los artistas nuevos y lo pone en sus canciones sin género. */
    @Scheduled(initialDelay = 90_000, fixedDelay = 30 * 60_000)
    public void enriquecer() {
        Map<String, List<CancionDTO>> sinGenero = new HashMap<>();
        Map<String, String> nombres = new HashMap<>();
        for (CancionDTO c : biblioteca.todas()) {
            String g = c.getGenero();
            if (g != null && !g.isBlank() && !"Desconocido".equalsIgnoreCase(g)) continue;
            String k = clave(c.getArtista());
            if (k.isEmpty()) continue;
            sinGenero.computeIfAbsent(k, x -> new ArrayList<>()).add(c);
            nombres.putIfAbsent(k, principal(c.getArtista()));
        }
        if (sinGenero.isEmpty()) return;
        int buscados = 0, actualizadas = 0;
        for (var e : sinGenero.entrySet()) {
            String k = e.getKey();
            Optional<ArtistaGenero> guardado = repo.findByClave(k);
            ArtistaGenero a;
            if (guardado.isPresent() && (guardado.get().getPrincipal() != null && !guardado.get().getPrincipal().isBlank()
                    || guardado.get().getFecha().isAfter(LocalDateTime.now().minusDays(7)))) {
                a = guardado.get(); // ya consultado (si no se encontró, se reintenta en una semana)
            } else {
                if (buscados >= 40) break; // no saturar MusicBrainz en una sola pasada
                buscados++;
                List<String> gen = buscarMusicBrainz(nombres.get(k));
                if (gen.isEmpty()) gen = buscarDeezer(nombres.get(k)); // segunda fuente
                a = guardado.orElseGet(() -> ArtistaGenero.builder().clave(k).build());
                a.setNombre(nombres.get(k));
                a.setGeneros(String.join(",", gen));
                a.setPrincipal(gen.isEmpty() ? "" : mostrar(gen.get(0)));
                a.setFecha(LocalDateTime.now());
                a = repo.save(a);
                cache.remove(k);
                try { Thread.sleep(1100); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
            if (a.getPrincipal() != null && !a.getPrincipal().isBlank()) {
                actualizadas += canciones.ponerGenero(e.getValue().stream().map(CancionDTO::getId).toList(), a.getPrincipal());
            }
        }
        if (actualizadas > 0) {
            biblioteca.invalidar();
            log.info("Géneros: {} artistas consultados, {} canciones con género nuevo", buscados, actualizadas);
        }
    }

    private List<String> buscarMusicBrainz(String artista) {
        try {
            String objetivo = BibliotecaService.normalizar(artista);
            String id = null;
            // Primero el nombre exacto; si no aparece, una búsqueda más flexible (acentos, mayúsculas…)
            for (String consulta : List.of("artist:\"" + artista.replace("\"", "") + "\"", artista.replace("\"", ""))) {
                JsonNode r = get("https://musicbrainz.org/ws/2/artist/?query=" + URLEncoder.encode(consulta, StandardCharsets.UTF_8) + "&limit=5&fmt=json");
                if (r != null) {
                    for (JsonNode a : r.path("artists")) {
                        String n = BibliotecaService.normalizar(a.path("name").asText());
                        if (a.path("score").asInt() >= 80 && (n.equals(objetivo) || n.contains(objetivo) || objetivo.contains(n))) { id = a.path("id").asText(); break; }
                    }
                }
                if (id != null) break;
                Thread.sleep(1100);
            }
            if (id == null) return List.of();
            Thread.sleep(1100);
            JsonNode d = get("https://musicbrainz.org/ws/2/artist/" + id + "?inc=genres+tags&fmt=json");
            if (d == null) return List.of();
            List<JsonNode> g = new ArrayList<>();
            d.path("genres").forEach(g::add);
            if (g.isEmpty()) d.path("tags").forEach(t -> { if (t.path("count").asInt() > 0) g.add(t); });
            g.sort(Comparator.comparingInt((JsonNode x) -> -x.path("count").asInt()));
            return g.stream().map(x -> x.path("name").asText().toLowerCase(Locale.ROOT).trim())
                    .filter(x -> !x.isEmpty() && x.length() <= 40).distinct().limit(8).toList();
        } catch (Exception ex) {
            log.debug("MusicBrainz ({}): {}", artista, ex.getMessage());
            return List.of();
        }
    }

    /** Deezer (sin clave): géneros del primer álbum que de verdad sea de ese artista. */
    private List<String> buscarDeezer(String artista) {
        try {
            String objetivo = BibliotecaService.normalizar(artista);
            JsonNode r = get("https://api.deezer.com/search/album?limit=8&q=" + URLEncoder.encode("artist:\"" + artista.replace("\"", "") + "\"", StandardCharsets.UTF_8));
            if (r == null) return List.of();
            for (JsonNode al : r.path("data")) {
                if (!BibliotecaService.normalizar(al.path("artist").path("name").asText()).equals(objetivo)) continue;
                JsonNode d = get("https://api.deezer.com/album/" + al.path("id").asText());
                if (d == null) continue;
                List<String> g = new ArrayList<>();
                d.path("genres").path("data").forEach(x -> g.add(x.path("name").asText().toLowerCase(Locale.ROOT).trim()));
                if (!g.isEmpty()) return g.stream().filter(x -> !x.isEmpty()).distinct().limit(5).toList();
            }
        } catch (Exception ex) {
            log.debug("Deezer ({}): {}", artista, ex.getMessage());
        }
        return List.of();
    }

    private JsonNode get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(12))
                .header("User-Agent", AGENTE).header("Accept", "application/json").GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        return res.statusCode() == 200 ? json.readTree(res.body()) : null;
    }

    // ═══════════════════ Nombres y familias ═══════════════════

    private static final Map<String, String> NOMBRES = Map.ofEntries(
            Map.entry("reggaeton", "Reggaetón"), Map.entry("trap latino", "Trap latino"), Map.entry("latin trap", "Trap latino"),
            Map.entry("latin", "Latino"), Map.entry("latin pop", "Pop latino"), Map.entry("urbano latino", "Urbano latino"),
            Map.entry("hip hop", "Hip hop"), Map.entry("rap", "Rap"), Map.entry("trap", "Trap"), Map.entry("r&b", "R&B"),
            Map.entry("contemporary r&b", "R&B"), Map.entry("alternative r&b", "R&B alternativo"), Map.entry("pop", "Pop"), Map.entry("rock", "Rock"),
            Map.entry("electronic", "Electrónica"), Map.entry("electronica", "Electrónica"), Map.entry("edm", "EDM"),
            Map.entry("house", "House"), Map.entry("techno", "Techno"), Map.entry("dance", "Dance"),
            Map.entry("regional mexicano", "Regional mexicano"), Map.entry("regional mexican", "Regional mexicano"),
            Map.entry("corridos tumbados", "Corridos tumbados"), Map.entry("corrido", "Corridos"), Map.entry("banda", "Banda"),
            Map.entry("norteño", "Norteño"), Map.entry("salsa", "Salsa"), Map.entry("cumbia", "Cumbia"), Map.entry("bachata", "Bachata"),
            Map.entry("merengue", "Merengue"), Map.entry("bolero", "Bolero"), Map.entry("ballad", "Balada"), Map.entry("balada", "Balada"),
            Map.entry("christian", "Cristiana"), Map.entry("christian music", "Cristiana"), Map.entry("worship", "Alabanza"),
            Map.entry("gospel", "Gospel"), Map.entry("soul", "Soul"), Map.entry("funk", "Funk"), Map.entry("jazz", "Jazz"),
            Map.entry("indie", "Indie"), Map.entry("alternative rock", "Rock alternativo"), Map.entry("dembow", "Dembow"),
            Map.entry("uk garage", "UK garage"), Map.entry("música mexicana", "Regional mexicano"), Map.entry("musica mexicana", "Regional mexicano"),
            Map.entry("rap/hip hop", "Hip hop"), Map.entry("electro", "Electrónica"), Map.entry("rock latino", "Rock latino"),
            Map.entry("r&b/soul", "R&B"), Map.entry("ambient", "Ambient"), Map.entry("pop rap", "Pop rap"));

    /** Nombre para mostrar de un género de MusicBrainz. */
    public static String mostrar(String g) {
        if (g == null || g.isBlank()) return "";
        String n = NOMBRES.get(g.toLowerCase(Locale.ROOT));
        if (n != null) return n;
        return Character.toUpperCase(g.charAt(0)) + g.substring(1);
    }

    /** Familia amplia de un género (para comparar canciones aunque el género exacto no coincida). */
    public static String familia(String genero) {
        String g = BibliotecaService.normalizar(genero);
        if (g.isEmpty() || g.equals("desconocido")) return "";
        if (g.matches(".*(reggaeton|reggaetón|dembow|trap latino|latin trap|urbano|perreo|latin hip hop).*")) return "urbano";
        if (g.matches(".*(corrido|regional|mexican|banda|norteno|norteño|sierreno|mariachi|ranchera|grupero).*")) return "regional";
        if (g.matches(".*(salsa|cumbia|bachata|merengue|tropical|vallenato).*")) return "tropical";
        if (g.matches(".*(christian|cristian|worship|alabanza|gospel|adoracion).*")) return "cristiana";
        if (g.matches(".*(hip hop|rap|trap|drill|grime).*")) return "hiphop";
        if (g.matches(".*(r b|rnb|soul|funk).*")) return "rnb";
        if (g.matches(".*(electr|house|techno|edm|garage|dance|trance|dubstep|ambient|drum and bass).*")) return "electronica";
        if (g.matches(".*(bolero|balada|ballad|romantic|romantica).*")) return "romantica";
        if (g.matches(".*(rock|metal|punk|indie|alternative|grunge).*")) return "rock";
        if (g.matches(".*(pop|latino|latin).*")) return "pop";
        if (g.matches(".*(jazz|blues|classical|clasica).*")) return "clasica";
        return g;
    }

    /** Parecido entre dos artistas por sus géneros (0 a 1). */
    public double parecido(String artistaA, String artistaB) {
        List<String> a = generosDe(artistaA), b = generosDe(artistaB);
        if (a.isEmpty() || b.isEmpty()) return 0;
        Set<String> sa = new HashSet<>(a), sb = new HashSet<>(b);
        Set<String> inter = new HashSet<>(sa);
        inter.retainAll(sb);
        Set<String> union = new HashSet<>(sa);
        union.addAll(sb);
        double jaccard = union.isEmpty() ? 0 : (double) inter.size() / union.size();
        // Las familias del género principal también cuentan
        Set<String> fa = a.stream().limit(3).map(GeneroService::familia).collect(Collectors.toSet());
        Set<String> fb = b.stream().limit(3).map(GeneroService::familia).collect(Collectors.toSet());
        fa.retainAll(fb);
        return Math.min(1, jaccard + (fa.isEmpty() ? 0 : 0.35));
    }
}
