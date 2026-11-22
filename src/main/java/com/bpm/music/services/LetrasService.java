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

/**
 * Letras de canciones: lectura, guardado y búsqueda en internet en varias fuentes (en orden):
 * LRCLIB (exacta, aproximada y por texto libre) → NetEase → syncedlyrics (Musixmatch, Megalobiz…).
 * Mejoras de la búsqueda: (1) se prefiere siempre la sincronizada: si una fuente solo tiene texto se sigue buscando;
 * (2) se prueban variantes del título y del artista (sin «feat.», «| Álbum», «(Official Video)», solo el artista
 * principal); (3) se comprueba la duración (±12 s) y que no sea otra versión (en vivo, remix…); (4) se limpia el LRC
 * (créditos, etiquetas [ar:]/[ti:], líneas vacías repetidas); (5) se guarda de qué fuente vino.
 */
@Service
public class LetrasService {

    private static final Logger log = LoggerFactory.getLogger(LetrasService.class);
    private static final String LRCLIB = "https://lrclib.net/api";

    private static final String NETEASE = "https://music.163.com/api";
    /** syncedlyrics (Python, en el mismo entorno que spotdl): Musixmatch, Megalobiz y más. */
    private final String syncedlyrics;

    private final CancionRepository repo;
    private final BibliotecaService biblioteca;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    /** Canciones ya buscadas sin éxito (no repetir la búsqueda en cada reproducción). */
    private final Map<Long, Long> sinResultado = new ConcurrentHashMap<>();

    public LetrasService(CancionRepository repo, BibliotecaService biblioteca,
                         @org.springframework.beans.factory.annotation.Value("${letras.syncedlyrics:${user.home}/spacemusic/spotdl-env/bin/syncedlyrics}") String syncedlyrics) {
        this.repo = repo;
        this.biblioteca = biblioteca;
        this.syncedlyrics = syncedlyrics;
    }

    /** Lo que se encontró en internet. */
    record Encontrada(String plano, String lrc, String fuente, String metodo, int diferencia, boolean instrumental) {
        boolean sincronizada() { return lrc != null && !lrc.isBlank(); }
    }

    /** Busca en todas las fuentes y devuelve la mejor (sincronizada si existe en alguna). */
    private final ThreadLocal<Long> plazo = new ThreadLocal<>();
    private final CacheTemporal<Long, Optional<Encontrada>> resultadosBusqueda = new CacheTemporal<>(300000, 1000);
    Encontrada buscarTodas(CancionDTO c) {
        return resultadosBusqueda.obtener(c.getId(), () -> Optional.ofNullable(buscarAcotada(c))).orElse(null);
    }
    private long restanteMillis() { return plazo.get() == null ? 10000 : Math.max(1, Math.min(10000, (plazo.get() - System.nanoTime()) / 1000000)); }
    private Encontrada buscarAcotada(CancionDTO c) {
        plazo.set(System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(18));
        try {
        Encontrada soloTexto = null;
        for (var fuente : List.<java.util.concurrent.Callable<Encontrada>>of(
                () -> deLrclib(buscarExacta(c), "LRCLIB", "artista + título + duración (búsqueda exacta)", 0),
                () -> aproximadaLrclib(c),
                () -> deNetease(c),
                () -> deSyncedlyrics(c))) {
            if (System.nanoTime() >= plazo.get()) break;
            try {
                Encontrada e = fuente.call();
                if (e == null) continue;
                if (e.instrumental() || e.sincronizada()) return e;
                if (soloTexto == null && e.plano() != null && !e.plano().isBlank()) soloTexto = e;
            } catch (Exception ex) {
                log.debug("Fuente de letras falló para {}: {}", c.getId(), ex.getMessage());
            }
        }
        return soloTexto;
        } finally { plazo.remove(); }
    }

    private Encontrada deLrclib(JsonNode hit, String fuente, String metodo, int dif) {
        if (hit == null) return null;
        if (hit.path("instrumental").asBoolean(false)) return new Encontrada("", "", fuente, metodo, dif, true);
        String lrc = limpiarLrc(hit.path("syncedLyrics").asText(""));
        String plano = hit.path("plainLyrics").asText("").trim();
        if (lrc.isBlank() && plano.isBlank()) return null;
        return new Encontrada(plano.isBlank() ? textoPlanoDeLrc(lrc) : plano, lrc, fuente, metodo, dif, false);
    }

    private Encontrada aproximadaLrclib(CancionDTO c) throws Exception {
        JsonNode hit = buscarAproximada(c);
        if (hit == null) {
            // Texto libre con el título limpio y el artista principal (encuentra títulos escritos distinto)
            HttpResponse<String> res = get(LRCLIB + "/search?q=" + enc(principal(c.getArtista()) + " " + limpiarTitulo(c.getNombre())));
            if (res.statusCode() == 200) hit = mejorDeLista(mapper.readTree(res.body()), c);
        }
        if (hit == null) return null;
        int dif = c.getDuracionSegundos() > 0 ? Math.abs(hit.path("duration").asInt(0) - c.getDuracionSegundos()) : 99;
        return deLrclib(hit, "LRCLIB", "búsqueda aproximada por artista y título (duración ±" + dif + " s)", dif);
    }

    /** NetEase Cloud Music (gratis, sin clave): búsqueda y letra LRC; se comprueban título, artista y duración. */
    private Encontrada deNetease(CancionDTO c) throws Exception {
        String q = principal(c.getArtista()) + " " + limpiarTitulo(c.getNombre());
        HttpResponse<String> res = get(NETEASE + "/cloudsearch/pc?type=1&limit=10&s=" + enc(q));
        if (res.statusCode() != 200) return null;
        JsonNode canciones = mapper.readTree(res.body()).path("result").path("songs");
        String baseLocal = com.bpm.music.externo.Coincidencias.base(limpiarTitulo(c.getNombre()));
        Set<String> artistasLocales = com.bpm.music.externo.Coincidencias.artistas(c.getArtista());
        for (JsonNode s : canciones) {
            if (!com.bpm.music.externo.Coincidencias.base(s.path("name").asText()).equals(baseLocal) || baseLocal.isEmpty()) continue;
            if (!com.bpm.music.externo.Coincidencias.versiones(s.path("name").asText()).equals(com.bpm.music.externo.Coincidencias.versiones(c.getNombre()))) continue;
            boolean artista = false;
            for (JsonNode a : s.path("ar")) {
                for (String n : com.bpm.music.externo.Coincidencias.artistas(a.path("name").asText()))
                    if (artistasLocales.stream().anyMatch(l -> l.contains(n) || n.contains(l))) artista = true;
            }
            if (!artista) continue;
            int dif = c.getDuracionSegundos() > 0 ? Math.abs(s.path("dt").asInt(0) / 1000 - c.getDuracionSegundos()) : 0;
            if (c.getDuracionSegundos() > 0 && dif > 12) continue;
            HttpResponse<String> l = get(NETEASE + "/song/lyric?lv=1&kv=1&tv=-1&id=" + s.path("id").asLong());
            if (l.statusCode() != 200) continue;
            String lrc = limpiarLrc(mapper.readTree(l.body()).path("lrc").path("lyric").asText(""));
            if (lrc.isBlank() || !MetadataService.LRC_TIMESTAMP.matcher(lrc).find()) continue;
            return new Encontrada(textoPlanoDeLrc(lrc), lrc, "NetEase", "NetEase: título + artista + duración (±" + dif + " s)", dif, false);
        }
        return null;
    }

    /** syncedlyrics (Musixmatch, Megalobiz…): solo la sincronizada; tope de 40 s. */
    private Encontrada deSyncedlyrics(CancionDTO c) throws Exception {
        if (!java.nio.file.Files.isExecutable(java.nio.file.Path.of(syncedlyrics))) return null;
        String q = principal(c.getArtista()) + " " + limpiarTitulo(c.getNombre());
        Process p = new ProcessBuilder(syncedlyrics, q, "--synced-only", "-p", "musixmatch", "netease", "megalobiz", "lrclib")
                .redirectErrorStream(false).start();
        p.getOutputStream().close();
        String salida;
        try (var in = p.getInputStream()) {
            if (!p.waitFor(restanteMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) { p.destroyForcibly(); return null; }
            salida = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String lrc = limpiarLrc(salida);
        if (!MetadataService.LRC_TIMESTAMP.matcher(lrc).find()) return null;
        return new Encontrada(textoPlanoDeLrc(lrc), lrc, "Musixmatch/Megalobiz", "syncedlyrics por artista y título", 99, false);
    }

    /** Artista principal (sin «feat.», «&», «, »…). */
    static String principal(String artista) {
        Set<String> a = com.bpm.music.externo.Coincidencias.artistas(artista);
        if (artista == null) return "";
        String primero = artista.split("(?i)\\s*(,|;|&| feat\\.? | ft\\.? | featuring | x | y | with )\\s*")[0].trim();
        return primero.isEmpty() && !a.isEmpty() ? a.iterator().next() : primero;
    }

    /** Quita créditos, etiquetas de cabecera y líneas vacías repetidas de un LRC. */
    static String limpiarLrc(String lrc) {
        if (lrc == null) return "";
        StringBuilder sb = new StringBuilder();
        String anterior = null;
        for (String linea : lrc.replace("\r\n", "\n").split("\n")) {
            String l = linea.trim();
            if (l.isEmpty()) continue;
            if (l.matches("(?i)^\\[(ar|ti|al|by|re|ve|length|au|#):.*\\]$")) continue;
            String texto = l.replaceAll("\\[[^\\]]*]", "").trim();
            if (texto.matches("(?i)^(作词|作曲|编曲|制作人|lyrics?( by)?|written by|composed by|composer|producers?|produced by|letra|m[uú]sica)\\s*[:：].*")) continue;
            if (texto.isEmpty() && anterior != null && anterior.isEmpty()) continue;
            sb.append(l).append('\n');
            anterior = texto;
        }
        return sb.toString().trim();
    }

    private JsonNode mejorDeLista(JsonNode arr, CancionDTO c) {
        if (arr == null || !arr.isArray()) return null;
        String base = com.bpm.music.externo.Coincidencias.base(limpiarTitulo(c.getNombre()));
        JsonNode mejor = null;
        int mejorDif = Integer.MAX_VALUE;
        for (JsonNode n : arr) {
            if (!com.bpm.music.externo.Coincidencias.base(n.path("trackName").asText()).equals(base)) continue;
            boolean sincronizada = !n.path("syncedLyrics").asText("").isBlank();
            if (!sincronizada && n.path("plainLyrics").asText("").isBlank()) continue;
            int dif = c.getDuracionSegundos() > 0 ? Math.abs(n.path("duration").asInt(0) - c.getDuracionSegundos()) : 0;
            if (!sincronizada) dif += 5;
            if (dif < mejorDif) { mejorDif = dif; mejor = n; }
        }
        return mejor != null && (c.getDuracionSegundos() == 0 || mejorDif <= 15) ? mejor : null;
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

    private com.bpm.music.organizador.RegistroMetadatos registro;

    private EventosService eventos;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setEventos(EventosService eventos) { this.eventos = eventos; }

    /** Lectura bloqueada + revisión evita pisar correcciones de otro editor. No cambia el texto plano. */
    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> guardarSincronia(Long id, Double segundos, String revision) {
        biblioteca.requerir(id);
        var filas = repo.letrasParaSincronia(id);
        if (filas.isEmpty()) throw new NoSuchElementException("Canción no encontrada");
        String plano = filas.get(0)[0] == null ? "" : filas.get(0)[0].toString();
        String anterior = filas.get(0)[1] == null ? "" : filas.get(0)[1].toString();
        if (revision == null || !revision.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Falta la revisión de la letra; vuelve a cargarla");
        if (!SincroniaLetra.revision(anterior).equals(revision))
            throw new IllegalStateException("La letra cambió en otro dispositivo. Recarga el ajuste antes de guardar");
        String nueva = SincroniaLetra.ajustar(anterior, segundos);
        repo.actualizarSincronia(id, nueva);
        anotar(id, "letraSincronizada", anterior, nueva);
        if (eventos != null) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCommit() {
                            for (Long uid : eventos.conectados().keySet()) eventos.enviar(uid, "letra", Map.of("id", id));
                        }
                    });
        }
        return respuesta(plano, nueva, null);
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setRegistro(com.bpm.music.organizador.RegistroMetadatos registro) { this.registro = registro; }

    private void anotar(Long id, String campo, Object antes, Object despues) {
        if (registro == null || java.util.Objects.equals(antes, despues)) return;
        Long uid = com.bpm.music.security.CurrentUser.idOrNull();
        registro.manual(id, campo, antes == null ? null : String.valueOf(antes), despues == null ? null : String.valueOf(despues), uid == null ? null : "usuario #" + uid);
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
        anotar(id, "letra", null, texto.length() > 120 ? texto.substring(0, 120) + "…" : texto);
        sinResultado.remove(id);
        biblioteca.invalidar();
        return respuesta(letra, lrc, null);
    }

    public void borrar(Long id) {
        biblioteca.requerir(id);
        repo.actualizarLetras(id, "", "");
        anotar(id, "letra", "(tenía letra)", "");
        biblioteca.invalidar();
    }

    /**
     * Busca la letra en LRCLIB por artista, título y duración.
     * Si la encuentra la guarda y la devuelve.
     */
    public Map<String, Object> buscarEnInternet(Long id, boolean forzar) {
        if (forzar) resultadosBusqueda.borrar(id);
        CancionDTO c = biblioteca.requerir(id);
        Long antes = sinResultado.get(id);
        if (!forzar && antes != null && System.currentTimeMillis() - antes < 24 * 3600_000L) {
            return Map.of("encontrada", false, "message", "No se encontró la letra en línea");
        }
        try {
            Encontrada e = buscarTodas(c);
            if (e == null) {
                sinResultado.put(id, System.currentTimeMillis());
                return Map.of("encontrada", false, "message", "No se encontró la letra en línea");
            }
            if (e.instrumental()) {
                return Map.of("encontrada", false, "instrumental", true, "message", "Es una canción instrumental");
            }
            String lrc = e.lrc() == null ? "" : e.lrc().trim();
            String plano = e.plano() == null ? "" : e.plano().trim();
            repo.actualizarLetras(id, plano.isBlank() ? textoPlanoDeLrc(lrc) : plano, lrc);
            if (registro != null) registro.automatico(id, "letra", lrc.isBlank() ? plano : lrc, e.fuente(), "Al abrir la letra: " + e.metodo());
            biblioteca.refrescarEnSegundoPlano();
            Map<String, Object> r = new HashMap<>(respuesta(plano, lrc, e.fuente()));
            r.put("encontrada", true);
            return r;
        } catch (Exception e) {
            log.debug("Error buscando letra de {}: {}", id, e.getMessage());
            return Map.of("encontrada", false, "message", "No se pudo conectar con el servicio de letras");
        }
    }

    /** Propuesta de letra para el completado automático (no guarda nada). null = sin resultado. */
    public record PropuestaLetra(String plano, String lrc, String metodo, int diferencia, boolean instrumental) {}

    public PropuestaLetra propuesta(CancionDTO c) throws Exception {
        Encontrada e = buscarTodas(c);
        if (e == null) return null;
        String metodo = e.fuente() + ": " + e.metodo();
        if (e.instrumental()) return new PropuestaLetra("", "", metodo, e.diferencia(), true);
        return new PropuestaLetra(e.plano(), e.lrc(), metodo, e.diferencia(), false);
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
                + "&artist_name=" + enc(principal(c.getArtista()));
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
                .timeout(Duration.ofMillis(restanteMillis()))
                .header("User-Agent", "SpaceMusic/2.0 (servidor personal de musica)")
                .GET().build();
        return http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    /** Quita "(Remastered 2011)", "[Official Video]", "- Live", "feat. X", "| Álbum" etc. del título. */
    static String limpiarTitulo(String t) {
        if (t == null) return "";
        int barra = t.indexOf(" | ");
        if (barra > 0) t = t.substring(0, barra);
        t = t.replaceAll("(?i)\\s*[(\\[](feat\\.?|ft\\.?|featuring|con|with)\\s[^)\\]]*[)\\]]", "");
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
        m.put("sincroniaGlobalSegundos", SincroniaLetra.segundos(lrc));
        m.put("revisionSincronia", SincroniaLetra.revision(lrc));
        if (fuente != null) m.put("fuente", fuente);
        return m;
    }
}
