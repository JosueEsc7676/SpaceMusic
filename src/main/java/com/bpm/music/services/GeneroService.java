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
        if (!buscando.compareAndSet(false, true)) return; // ya hay una búsqueda en marcha
        try { enriquecerAhora(); } finally { buscando.set(false); }
    }

    private final java.util.concurrent.atomic.AtomicBoolean buscando = new java.util.concurrent.atomic.AtomicBoolean();

    /** Administración: lanza la búsqueda de géneros ahora, en segundo plano. */
    public boolean buscarEnSegundoPlano() {
        if (buscando.get()) return false;
        Thread.ofVirtual().start(this::enriquecer);
        return true;
    }

    public boolean estaBuscando() { return buscando.get(); }

    /** Administración: qué géneros hay en el servidor y qué artistas no tienen género. */
    public Map<String, Object> resumenAdmin() {
        Map<String, int[]> porGenero = new HashMap<>();
        Map<String, Set<String>> artistasPorGenero = new HashMap<>();
        Map<String, Integer> sinGenero = new HashMap<>();
        Map<String, String> nombreSin = new HashMap<>();
        int total = 0, conGenero = 0;
        for (CancionDTO c : biblioteca.todas()) {
            total++;
            String g = c.getGenero();
            if (g == null || g.isBlank() || "Desconocido".equalsIgnoreCase(g)) {
                String k = clave(c.getArtista());
                if (k.isEmpty()) continue;
                sinGenero.merge(k, 1, Integer::sum);
                nombreSin.putIfAbsent(k, principal(c.getArtista()));
                continue;
            }
            conGenero++;
            porGenero.computeIfAbsent(g, x -> new int[1])[0]++;
            artistasPorGenero.computeIfAbsent(g, x -> new HashSet<>()).add(principal(c.getArtista()));
        }
        List<Map<String, Object>> generos = new ArrayList<>();
        porGenero.forEach((g, n) -> {
            Set<String> arts = artistasPorGenero.get(g);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("genero", g);
            m.put("familia", familia(g));
            m.put("canciones", n[0]);
            m.put("artistas", arts.size());
            m.put("ejemplos", arts.stream().sorted().limit(6).toList());
            generos.add(m);
        });
        generos.sort(Comparator.comparingInt((Map<String, Object> m) -> -(int) m.get("canciones")));
        List<Map<String, Object>> sin = sinGenero.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(150)
                .map(e -> Map.<String, Object>of("artista", nombreSin.get(e.getKey()), "canciones", e.getValue()))
                .toList();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("totalCanciones", total);
        r.put("conGenero", conGenero);
        r.put("generos", generos);
        r.put("sinGenero", sin);
        r.put("artistasSinGenero", sinGenero.size());
        r.put("buscando", buscando.get());
        return r;
    }

    /** Administración: fija a mano el género de un artista (y de todas sus canciones). */
    public int fijarGenero(String artista, String genero) {
        String k = clave(artista);
        String g = genero == null ? "" : genero.trim();
        if (k.isEmpty() || g.isEmpty() || g.length() > 60) throw new IllegalArgumentException("Artista o género inválido");
        g = mostrar(g.toLowerCase(Locale.ROOT));
        ArtistaGenero a = repo.findByClave(k).orElseGet(() -> ArtistaGenero.builder().clave(k).build());
        a.setNombre(principal(artista));
        a.setGeneros(g.toLowerCase(Locale.ROOT));
        a.setPrincipal(g);
        a.setFecha(LocalDateTime.now());
        repo.save(a);
        cache.remove(k);
        List<Long> ids = biblioteca.todas().stream().filter(c -> clave(c.getArtista()).equals(k)).map(CancionDTO::getId).toList();
        int n = ids.isEmpty() ? 0 : canciones.cambiarGenero(ids, g);
        biblioteca.invalidar();
        log.info("Género de {} cambiado a mano a {} ({} canciones)", artista, g, n);
        return n;
    }

    private void enriquecerAhora() {
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

    // ═══════════════════ Buscar por género o estado de ánimo ═══════════════════

    private record Estilo(String titulo, Set<String> familias, List<String> palabras) {}

    /** Palabras que la gente escribe → qué música buscar. */
    private static final Map<String, Estilo> ESTILOS = new LinkedHashMap<>();
    static {
        Estilo urbano = new Estilo("Reggaetón y urbano", Set.of("urbano"), List.of("reggaeton", "trap latino", "dembow"));
        for (String k : List.of("reggaeton", "regueton", "reguetón", "reggaetón", "perreo", "urbano", "dembow", "trap latino")) ESTILOS.put(k, urbano);
        Estilo regional = new Estilo("Regional mexicano", Set.of("regional"), List.of("regional", "banda", "corrido", "norteño", "mexicana"));
        for (String k : List.of("banda", "corridos", "corrido", "regional", "norteño", "norteno", "mexicana", "sierreño", "sierreno", "tumbados", "mariachi", "rancheras")) ESTILOS.put(k, regional);
        Estilo tropical = new Estilo("Tropical: salsa, bachata y cumbia", Set.of("tropical"), List.of("salsa", "bachata", "cumbia", "merengue"));
        for (String k : List.of("bachata", "salsa", "cumbia", "merengue", "tropical", "vallenato")) ESTILOS.put(k, tropical);
        Estilo hiphop = new Estilo("Hip hop y rap", Set.of("hiphop"), List.of("hip hop", "rap", "trap"));
        for (String k : List.of("rap", "hiphop", "hip hop", "trap", "drill")) ESTILOS.put(k, hiphop);
        Estilo electronica = new Estilo("Electrónica", Set.of("electronica"), List.of("electr", "house", "techno", "edm"));
        for (String k : List.of("electronica", "electrónica", "house", "techno", "edm", "dj", "electro")) ESTILOS.put(k, electronica);
        Estilo rnb = new Estilo("R&B", Set.of("rnb"), List.of("r&b", "soul"));
        for (String k : List.of("rnb", "r&b", "soul")) ESTILOS.put(k, rnb);
        Estilo rock = new Estilo("Rock", Set.of("rock"), List.of("rock", "metal", "indie"));
        for (String k : List.of("rock", "metal", "indie", "alternativo", "punk")) ESTILOS.put(k, rock);
        Estilo pop = new Estilo("Pop", Set.of("pop"), List.of("pop"));
        ESTILOS.put("pop", pop);
        Estilo cristiana = new Estilo("Música cristiana", Set.of("cristiana"), List.of("cristian", "alabanza", "worship", "gospel"));
        for (String k : List.of("cristiana", "cristiano", "alabanza", "alabanzas", "adoracion", "adoración", "gospel", "worship")) ESTILOS.put(k, cristiana);
        Estilo romantica = new Estilo("Romántica", Set.of("romantica", "rnb"), List.of("balada", "bolero", "romant"));
        for (String k : List.of("romantica", "romántica", "romantico", "amor", "baladas", "balada", "boleros", "bolero", "enamorado")) ESTILOS.put(k, romantica);
        // Estados de ánimo
        Estilo relajante = new Estilo("Música relajante", Set.of("rnb", "romantica", "clasica"), List.of("ambient", "lo-fi", "chill", "soul", "jazz"));
        for (String k : List.of("relajante", "relajar", "relax", "tranquila", "tranquilo", "calma", "chill", "dormir", "estudiar", "suave", "lofi", "lo fi")) ESTILOS.put(k, relajante);
        Estilo fiesta = new Estilo("Para la fiesta", Set.of("urbano", "tropical", "electronica"), List.of("dance", "reggaeton", "house"));
        for (String k : List.of("fiesta", "bailar", "party", "antro", "rumba", "perrear", "fiestera", "bailable")) ESTILOS.put(k, fiesta);
        Estilo triste = new Estilo("Para el despecho", Set.of("romantica", "regional"), List.of("balada", "bolero"));
        for (String k : List.of("triste", "despecho", "desamor", "llorar", "nostalgia", "nostalgica")) ESTILOS.put(k, triste);
        Estilo gym = new Estilo("Con energía", Set.of("hiphop", "urbano", "electronica", "rock"), List.of("trap", "edm"));
        for (String k : List.of("gym", "gimnasio", "entrenar", "motivacion", "motivación", "energia", "energía", "correr", "ejercicio")) ESTILOS.put(k, gym);
    }

    /**
     * Si la búsqueda es un género o un estado de ánimo ("banda", "música relajante", "reggaetón"),
     * devuelve un título y las canciones que encajan. Si no, null.
     */
    public Map<String, Object> buscarPorEstilo(String consulta) {
        String q = BibliotecaService.normalizar(consulta).replaceAll("\b(musica|music|canciones|de|para|el|la|los|las|un|una)\b", " ").trim().replaceAll("\s+", " ");
        if (q.length() < 3) return null;
        Estilo e = ESTILOS.get(q);
        if (e == null) for (String palabra : q.split(" ")) { e = ESTILOS.get(palabra); if (e != null) break; }
        List<CancionDTO> todas = biblioteca.todas();
        List<CancionDTO> r = new ArrayList<>();
        String titulo;
        if (e != null) {
            final Estilo est = e;
            for (CancionDTO c : todas) {
                String g = BibliotecaService.normalizar(c.getGenero());
                if (g.isEmpty() || g.equals("desconocido")) continue;
                if (est.familias().contains(familia(c.getGenero())) || est.palabras().stream().anyMatch(g::contains)) r.add(c);
            }
            titulo = est.titulo();
        } else {
            // ¿Coincide con un género que existe en el servidor? ("bolero beat", "dancehall"…)
            for (CancionDTO c : todas) if (BibliotecaService.normalizar(c.getGenero()).contains(q)) r.add(c);
            if (r.isEmpty()) return null;
            titulo = mostrar(r.get(0).getGenero());
        }
        if (r.isEmpty()) return Map.of("titulo", titulo, "canciones", List.of(), "vacio", true);
        // Las más escuchadas primero, con algo de variedad entre artistas
        r.sort(Comparator.comparingInt(CancionDTO::getReproducciones).reversed());
        Map<String, Integer> porArtista = new HashMap<>();
        List<CancionDTO> elegidas = new ArrayList<>(), resto = new ArrayList<>();
        for (CancionDTO c : r) {
            int n = porArtista.merge(BibliotecaService.normalizar(c.getArtista()), 1, Integer::sum);
            (n <= 4 ? elegidas : resto).add(c);
        }
        elegidas.addAll(resto);
        return Map.of("titulo", titulo, "canciones", elegidas.stream().limit(50).toList(), "total", r.size());
    }
}
