package com.bpm.music.services;

import com.bpm.music.dtos.AlbumDTO;
import com.bpm.music.dtos.ArtistaDTO;
import com.bpm.music.dtos.CancionDTO;
import com.bpm.music.repositories.CancionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Mantiene en memoria una vista ligera de toda la biblioteca y calcula
 * búsquedas, álbumes, artistas y géneros sin consultar la base de datos
 * en cada petición. Se reconstruye cuando cambia la biblioteca.
 */
@Service
public class BibliotecaService {

    private static final Logger log = LoggerFactory.getLogger(BibliotecaService.class);
    private static final Set<String> ALBUM_SUELTO = Set.of("single", "singles", "desconocido", "unknown", "");
    private static final Set<String> GENERO_VACIO = Set.of("desconocido", "unknown", "other", "otro", "");

    private final CancionRepository repo;
    private volatile Snapshot snapshot;

    public BibliotecaService(CancionRepository repo) {
        this.repo = repo;
    }

    // ═════════════════════════ Snapshot ═════════════════════════

    private record Entrada(CancionDTO c, String nombre, String artista, String album, String genero,
                           String[] palabrasNombre, String[] palabrasArtista, String[] palabrasAlbum) {}

    private record Snapshot(List<CancionDTO> canciones, Map<Long, CancionDTO> porId, List<Entrada> indice,
                            List<AlbumDTO> albumes, Map<String, AlbumDTO> albumPorClave,
                            List<ArtistaDTO> artistas, Map<String, List<CancionDTO>> cancionesPorArtista,
                            Map<String, ArtistaDTO> artistaPorClave, List<Map<String, Object>> generos,
                            Map<String, List<CancionDTO>> cancionesPorGenero) {}

    private Snapshot snap() {
        Snapshot s = snapshot;
        if (s != null) return s;
        synchronized (this) {
            if (snapshot == null) snapshot = construir();
            return snapshot;
        }
    }

    /** Marca la caché como desactualizada; se reconstruye en la próxima consulta. */
    public void invalidar() {
        snapshot = null;
    }

    private Snapshot construir() {
        long t0 = System.currentTimeMillis();
        List<CancionDTO> todas = new ArrayList<>(repo.findAllLigero());
        todas.sort(Comparator.comparing(CancionDTO::getId));
        Map<Long, CancionDTO> porId = new HashMap<>(todas.size() * 2);
        List<Entrada> indice = new ArrayList<>(todas.size());
        for (CancionDTO c : todas) {
            porId.put(c.getId(), c);
            String n = normalizar(c.getNombre()), a = normalizar(c.getArtista()),
                    al = normalizar(c.getAlbum()), g = normalizar(c.getGenero());
            indice.add(new Entrada(c, n, a, al, g, palabras(n), palabras(a), palabras(al)));
        }

        // ── Álbumes ──
        Map<String, List<CancionDTO>> porAlbum = new LinkedHashMap<>();
        for (CancionDTO c : todas) {
            String clave = normalizar(c.getAlbum());
            if (ALBUM_SUELTO.contains(clave)) continue;
            porAlbum.computeIfAbsent(clave, k -> new ArrayList<>()).add(c);
        }
        Map<String, AlbumDTO> albumPorClave = new HashMap<>();
        List<AlbumDTO> albumes = new ArrayList<>();
        for (var e : porAlbum.entrySet()) {
            List<CancionDTO> cs = e.getValue();
            cs.sort(ORDEN_ALBUM);
            AlbumDTO al = new AlbumDTO(
                    masComun(cs, CancionDTO::getAlbum),
                    artistaDeAlbum(cs),
                    cs.stream().map(CancionDTO::getAnio).filter(Objects::nonNull).min(Integer::compare).orElse(null),
                    cs.size(),
                    cs.stream().mapToInt(CancionDTO::getDuracionSegundos).sum(),
                    cs.stream().mapToInt(CancionDTO::getReproducciones).sum(),
                    portadaRepresentativa(cs),
                    Collections.unmodifiableList(cs));
            albumes.add(al);
            albumPorClave.put(e.getKey(), al);
        }
        albumes.sort(Comparator.comparing(a -> normalizar(a.nombre())));

        // ── Artistas ──
        Map<String, List<CancionDTO>> porArtista = new HashMap<>();
        for (CancionDTO c : todas) {
            porArtista.computeIfAbsent(normalizar(c.getArtista()), k -> new ArrayList<>()).add(c);
        }
        Map<String, ArtistaDTO> artistaPorClave = new HashMap<>();
        List<ArtistaDTO> artistas = new ArrayList<>();
        for (var e : porArtista.entrySet()) {
            List<CancionDTO> cs = e.getValue();
            ArtistaDTO ar = new ArtistaDTO(
                    masComun(cs, CancionDTO::getArtista),
                    cs.size(),
                    (int) cs.stream().map(c -> normalizar(c.getAlbum())).filter(k -> !ALBUM_SUELTO.contains(k)).distinct().count(),
                    cs.stream().mapToInt(CancionDTO::getReproducciones).sum(),
                    cs.stream().mapToInt(CancionDTO::getDuracionSegundos).sum(),
                    portadaRepresentativa(cs));
            artistas.add(ar);
            artistaPorClave.put(e.getKey(), ar);
        }
        artistas.sort(Comparator.comparingInt(ArtistaDTO::totalReproducciones).reversed()
                .thenComparing(a -> normalizar(a.nombre())));

        // ── Géneros ──
        Map<String, List<CancionDTO>> porGenero = new HashMap<>();
        for (CancionDTO c : todas) {
            String g = normalizar(c.getGenero());
            if (GENERO_VACIO.contains(g)) continue;
            porGenero.computeIfAbsent(g, k -> new ArrayList<>()).add(c);
        }
        List<Map<String, Object>> generos = porGenero.values().stream()
                .sorted(Comparator.comparingInt((List<CancionDTO> l) -> l.size()).reversed())
                .map(cs -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("nombre", masComun(cs, CancionDTO::getGenero));
                    m.put("totalCanciones", cs.size());
                    m.put("reproducciones", cs.stream().mapToInt(CancionDTO::getReproducciones).sum());
                    m.put("portadaUrl", portadaRepresentativa(cs));
                    return m;
                })
                .collect(Collectors.toList());

        log.info("Biblioteca en memoria: {} canciones, {} álbumes, {} artistas ({} ms)",
                todas.size(), albumes.size(), artistas.size(), System.currentTimeMillis() - t0);

        return new Snapshot(Collections.unmodifiableList(todas), porId, indice, albumes, albumPorClave,
                artistas, porArtista, artistaPorClave, generos, porGenero);
    }

    private static final Comparator<CancionDTO> ORDEN_ALBUM = Comparator
            .comparing((CancionDTO c) -> c.getNumeroPista() == null ? Integer.MAX_VALUE : c.getNumeroPista())
            .thenComparing(c -> normalizar(c.getNombre()));

    private static <T> String masComun(List<CancionDTO> cs, Function<CancionDTO, String> f) {
        return cs.stream().map(f).filter(Objects::nonNull)
                .collect(Collectors.groupingBy(s -> s, Collectors.counting()))
                .entrySet().stream().max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse("");
    }

    private static String artistaDeAlbum(List<CancionDTO> cs) {
        Map<String, Long> conteo = cs.stream()
                .collect(Collectors.groupingBy(c -> normalizar(c.getArtista()), Collectors.counting()));
        var mejor = conteo.entrySet().stream().max(Map.Entry.comparingByValue()).orElse(null);
        if (mejor == null) return "Desconocido";
        if (conteo.size() > 1 && mejor.getValue() * 2 < cs.size()) return "Varios artistas";
        return cs.stream().filter(c -> normalizar(c.getArtista()).equals(mejor.getKey()))
                .findFirst().map(CancionDTO::getArtista).orElse("Desconocido");
    }

    /** Portada de la canción más escuchada que tenga portada propia. */
    private static String portadaRepresentativa(List<CancionDTO> cs) {
        return cs.stream()
                .filter(c -> c.isTienePortada() || (c.getPortadaUrl() != null && c.getPortadaUrl().startsWith("http")))
                .max(Comparator.comparingInt(CancionDTO::getReproducciones))
                .or(() -> cs.stream().findFirst())
                .map(CancionDTO::getPortadaUrl).orElse(null);
    }

    // ═════════════════════════ Consultas ═════════════════════════

    public List<CancionDTO> todas() {
        return snap().canciones();
    }

    public int total() {
        return snap().canciones().size();
    }

    public Optional<CancionDTO> porId(Long id) {
        return Optional.ofNullable(snap().porId().get(id));
    }

    public CancionDTO requerir(Long id) {
        return porId(id).orElseThrow(() -> new NoSuchElementException("Canción no encontrada"));
    }

    /** Convierte IDs en canciones manteniendo el orden e ignorando las que ya no existen. */
    public List<CancionDTO> porIds(Collection<? extends Number> ids) {
        Map<Long, CancionDTO> m = snap().porId();
        List<CancionDTO> out = new ArrayList<>(ids.size());
        for (Number n : ids) {
            if (n == null) continue;
            CancionDTO c = m.get(n.longValue());
            if (c != null) out.add(c);
        }
        return out;
    }

    public List<CancionDTO> top(int n) {
        return snap().canciones().stream()
                .filter(c -> c.getReproducciones() > 0)
                .sorted(Comparator.comparingInt(CancionDTO::getReproducciones).reversed())
                .limit(n).toList();
    }

    public List<CancionDTO> recienAgregadas(int n) {
        return snap().canciones().stream()
                .sorted(Comparator.comparing(CancionDTO::getFechaAgregada,
                        Comparator.nullsFirst(Comparator.naturalOrder())).reversed()
                        .thenComparing(CancionDTO::getId, Comparator.reverseOrder()))
                .limit(n).toList();
    }

    public List<AlbumDTO> albumes() {
        return snap().albumes().stream().map(AlbumDTO::sinCanciones).toList();
    }

    public Optional<AlbumDTO> album(String nombre) {
        return Optional.ofNullable(snap().albumPorClave().get(normalizar(nombre)));
    }

    public List<AlbumDTO> albumesRecientes(int n) {
        return snap().albumes().stream()
                .sorted(Comparator.comparing((AlbumDTO a) -> a.canciones().stream()
                        .map(CancionDTO::getFechaAgregada).filter(Objects::nonNull)
                        .max(Comparator.naturalOrder()).orElse(null),
                        Comparator.nullsFirst(Comparator.naturalOrder())).reversed())
                .limit(n).map(AlbumDTO::sinCanciones).toList();
    }

    public List<ArtistaDTO> artistas() {
        return snap().artistas();
    }

    public Optional<Map<String, Object>> artista(String nombre) {
        Snapshot s = snap();
        String clave = normalizar(nombre);
        List<CancionDTO> cs = s.cancionesPorArtista().get(clave);
        if (cs == null || cs.isEmpty()) return Optional.empty();
        ArtistaDTO info = s.artistaPorClave().get(clave);

        List<CancionDTO> populares = cs.stream()
                .sorted(Comparator.comparingInt(CancionDTO::getReproducciones).reversed()
                        .thenComparing(c -> normalizar(c.getNombre())))
                .limit(10).toList();

        // Álbumes donde aparece el artista
        Set<String> clavesAlbum = cs.stream().map(c -> normalizar(c.getAlbum()))
                .filter(k -> !ALBUM_SUELTO.contains(k)).collect(Collectors.toCollection(LinkedHashSet::new));
        List<AlbumDTO> albumes = clavesAlbum.stream().map(k -> s.albumPorClave().get(k)).filter(Objects::nonNull)
                .sorted(Comparator.comparing((AlbumDTO a) -> a.anio() == null ? 0 : a.anio()).reversed())
                .map(AlbumDTO::sinCanciones).toList();

        List<CancionDTO> sencillos = cs.stream().filter(c -> ALBUM_SUELTO.contains(normalizar(c.getAlbum())))
                .sorted(Comparator.comparing(c -> normalizar(c.getNombre()))).toList();

        // Artistas relacionados: comparten género
        Set<String> generosArtista = cs.stream().map(c -> normalizar(c.getGenero()))
                .filter(g -> !GENERO_VACIO.contains(g)).collect(Collectors.toSet());
        List<ArtistaDTO> relacionados = s.artistas().stream()
                .filter(a -> !normalizar(a.nombre()).equals(clave))
                .filter(a -> s.cancionesPorArtista().getOrDefault(normalizar(a.nombre()), List.of()).stream()
                        .anyMatch(c -> generosArtista.contains(normalizar(c.getGenero()))))
                .limit(8).toList();

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("nombre", info.nombre());
        r.put("totalCanciones", info.totalCanciones());
        r.put("totalAlbumes", info.totalAlbumes());
        r.put("totalReproducciones", info.totalReproducciones());
        r.put("duracionTotal", info.duracionTotal());
        r.put("portadaUrl", info.portadaUrl());
        r.put("populares", populares);
        r.put("albumes", albumes);
        r.put("sencillos", sencillos);
        r.put("canciones", cs.stream().sorted(Comparator.comparing((CancionDTO c) -> normalizar(c.getAlbum()))
                .thenComparing(ORDEN_ALBUM)).toList());
        r.put("relacionados", relacionados);
        return Optional.of(r);
    }

    public List<CancionDTO> cancionesDeArtista(String nombre) {
        return snap().cancionesPorArtista().getOrDefault(normalizar(nombre), List.of());
    }

    public List<Map<String, Object>> generos() {
        return snap().generos();
    }

    public List<CancionDTO> cancionesDeGenero(String genero) {
        return snap().cancionesPorGenero().getOrDefault(normalizar(genero), List.of());
    }

    /** Suma una reproducción en memoria (la BD se actualiza aparte). */
    public void sumarReproduccion(Long id) {
        CancionDTO c = snap().porId().get(id);
        if (c != null) c.setReproducciones(c.getReproducciones() + 1);
    }

    // ═════════════════════════ Búsqueda ═════════════════════════

    public List<CancionDTO> buscarCanciones(String consulta, int limite) {
        String q = normalizar(consulta);
        if (q.isEmpty()) return List.of();
        String[] tokens = palabras(q);
        record Res(CancionDTO c, double score) {}
        List<Res> res = new ArrayList<>();
        for (Entrada e : snap().indice()) {
            double s = puntuar(e, q, tokens);
            if (s > 0) res.add(new Res(e.c(), s + Math.min(e.c().getReproducciones(), 100) / 25.0));
        }
        res.sort(Comparator.comparingDouble(Res::score).reversed());
        return res.stream().limit(limite).map(Res::c).toList();
    }

    public Map<String, Object> buscarTodo(String consulta) {
        String q = normalizar(consulta);
        Map<String, Object> r = new LinkedHashMap<>();
        if (q.isEmpty()) {
            r.put("canciones", List.of());
            r.put("artistas", List.of());
            r.put("albumes", List.of());
            return r;
        }
        String[] tokens = palabras(q);
        Snapshot s = snap();

        List<ArtistaDTO> artistas = s.artistas().stream()
                .map(a -> Map.entry(a, puntuarTexto(normalizar(a.nombre()), q, tokens)))
                .filter(e -> e.getValue() > 0)
                .sorted(Map.Entry.<ArtistaDTO, Double>comparingByValue().reversed()
                        .thenComparing(e -> -e.getKey().totalReproducciones()))
                .limit(12).map(Map.Entry::getKey).toList();

        List<AlbumDTO> albumes = s.albumes().stream()
                .map(a -> Map.entry(a, Math.max(puntuarTexto(normalizar(a.nombre()), q, tokens),
                        puntuarTexto(normalizar(a.nombre() + " " + a.artista()), q, tokens) * 0.8)))
                .filter(e -> e.getValue() > 0)
                .sorted(Map.Entry.<AlbumDTO, Double>comparingByValue().reversed())
                .limit(12).map(e -> e.getKey().sinCanciones()).toList();

        r.put("canciones", buscarCanciones(consulta, 60));
        r.put("artistas", artistas);
        r.put("albumes", albumes);
        return r;
    }

    private static double puntuar(Entrada e, String q, String[] tokens) {
        double score = 0;
        if (e.nombre().equals(q)) score += 100;
        else if (e.nombre().startsWith(q)) score += 60;
        if (e.artista().equals(q)) score += 50;
        else if (e.artista().startsWith(q)) score += 30;
        if (e.album().startsWith(q)) score += 15;

        for (String t : tokens) {
            double mejor = 0;
            mejor = Math.max(mejor, coincide(e.palabrasNombre(), e.nombre(), t, 12, 6, 3));
            mejor = Math.max(mejor, coincide(e.palabrasArtista(), e.artista(), t, 9, 4, 2));
            mejor = Math.max(mejor, coincide(e.palabrasAlbum(), e.album(), t, 5, 2, 1));
            if (mejor == 0 && e.genero().startsWith(t)) mejor = 2;
            if (mejor == 0) return 0; // todas las palabras deben aparecer en algún campo
            score += mejor;
        }
        return score;
    }

    private static double puntuarTexto(String texto, String q, String[] tokens) {
        double score = 0;
        if (texto.equals(q)) score += 100;
        else if (texto.startsWith(q)) score += 50;
        String[] ps = palabras(texto);
        for (String t : tokens) {
            double m = coincide(ps, texto, t, 10, 4, 2);
            if (m == 0) return 0;
            score += m;
        }
        return score;
    }

    /** Puntúa una palabra de búsqueda: inicio de palabra > contiene > parecida (1 error). */
    private static double coincide(String[] palabras, String texto, String t, double inicio, double contiene, double difuso) {
        for (String p : palabras) if (p.startsWith(t)) return inicio;
        if (t.length() >= 2 && texto.contains(t)) return contiene;
        if (t.length() >= 4) {
            for (String p : palabras) {
                if (Math.abs(p.length() - t.length()) <= 1 && distancia(p, t) <= 1) return difuso;
                if (p.length() > t.length() && distancia(p.substring(0, t.length()), t) <= 1) return difuso * 0.8;
            }
        }
        return 0;
    }

    /** Distancia de Levenshtein (para tolerar errores de escritura). */
    private static int distancia(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = cur; cur = tmp;
        }
        return prev[b.length()];
    }

    // ═════════════════════════ Texto ═════════════════════════

    /** Minúsculas, sin acentos y sin signos: "Canción Ñandú!" → "cancion nandu". */
    public static String normalizar(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        n = n.toLowerCase(Locale.ROOT).replace('&', ' ').replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
        return n;
    }

    private static String[] palabras(String normalizado) {
        return normalizado.isEmpty() ? new String[0] : normalizado.split(" ");
    }
}
