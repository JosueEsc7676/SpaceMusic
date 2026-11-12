package com.bpm.music.services;

import com.bpm.music.dtos.CancionDTO;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.Year;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Estilos musicales "inteligentes". Muchas canciones traen un género demasiado general ("Latino": más de mil
 * canciones de reggaetón, bachata, pop y regional juntas). Aquí cada canción recibe un estilo concreto:
 *   1. su propio género, si es específico ("Bachata", "Hip hop"…);
 *   2. si es general, los géneros de su artista (MusicBrainz: Romeo Santos → bachata);
 *   3. si tampoco, el estilo que más tienen las demás canciones de ese artista.
 * Con eso se arman Explorar, las páginas de género y todas las listas automáticas, y se ordenan
 * "con fluidez": sin saltar de un estilo a otro sin sentido ni repetir artista seguido.
 */
@Service
public class EstilosService {

    /** Un estilo: clave, nombre visible, familia amplia (para parecidos) y patrón sobre el género normalizado. */
    public record Estilo(String clave, String nombre, String familia, Pattern patron) {}

    // El orden importa: lo más específico primero ("trap latino" antes que "trap", "latin pop" antes que "pop")
    private static final List<Estilo> ESTILOS = List.of(
            e("infantil", "Infantil", "infantil", "children|infantil|kids|nursery|ninos|lullab|cuna"),
            e("cristiana", "Cristiana", "cristiana", "christian|cristian|worship|alabanza|gospel|adoracion"),
            e("corridos", "Corridos tumbados", "regional", "corrido|tumbado|sierreno|belico"),
            e("regional", "Regional mexicano", "regional", "regional|banda|norteno|mariachi|ranchera|grupero|musica mexicana|mexican"),
            e("bachata", "Bachata", "tropical", "bachata"),
            e("salsa", "Salsa", "tropical", "salsa"),
            e("merengue", "Merengue", "tropical", "merengue"),
            e("cumbia", "Cumbia y vallenato", "tropical", "cumbia|vallenato|tropical"),
            e("trap-latino", "Trap latino", "urbano", "trap latino|latin trap|latin hip hop|rap latino|trap argentino|trap en espanol"),
            e("reggaeton", "Reggaetón", "urbano", "reggaeton|regueton|dembow|perreo|urbano latino|latin urban|moombahton|neoperreo"),
            e("dancehall", "Reggae y dancehall", "urbano", "dancehall|reggae|afrobeat"),
            e("baladas", "Baladas y boleros", "romantica", "balada|ballad|bolero|romantic|cancion melodica|latin ballad"),
            e("pop-latino", "Pop latino", "pop", "latin pop|pop latino|pop en espanol|tropipop"),
            e("rock-latino", "Rock en español", "rock", "rock latino|rock en espanol|latin rock"),
            e("trap", "Trap", "hiphop", "trap|drill|emo rap|rage|plugg"),
            e("hiphop", "Hip hop", "hiphop", "hip hop|hiphop|rap|grime|boom bap"),
            e("rnb", "R&B", "rnb", "r b|rnb|soul|funk|neo soul"),
            e("electronica", "Electrónica", "electronica", "electr|house|techno|edm|garage|dance|trance|dubstep|ambient|drum and bass|synth"),
            e("rock", "Rock", "rock", "rock|metal|punk|grunge|alternative|alternativo|indie rock|emo"),
            e("pop", "Pop", "pop", "pop"),
            e("jazz", "Jazz y clásica", "clasica", "jazz|blues|classical|clasica|orchestra")
    );
    /** Géneros que no dicen nada concreto: se busca el estilo por el artista. */
    private static final Pattern GENERAL = Pattern.compile("^(latino|latin|latina|musica latina|latin music|world|varios|otros?|desconocido|unknown|musica|music|)$");
    public static final Estilo SIN_ESTILO = new Estilo("latino", "Latino variado", "pop", Pattern.compile("$^"));
    private static final Map<String, Estilo> POR_CLAVE = new LinkedHashMap<>();
    static { ESTILOS.forEach(x -> POR_CLAVE.put(x.clave(), x)); POR_CLAVE.put(SIN_ESTILO.clave(), SIN_ESTILO); }

    private static Estilo e(String clave, String nombre, String familia, String regex) {
        return new Estilo(clave, nombre, familia, Pattern.compile(".*\\b(" + regex + ").*"));
    }

    private final BibliotecaService biblioteca;
    private final GeneroService generos;

    public EstilosService(BibliotecaService biblioteca, GeneroService generos) {
        this.biblioteca = biblioteca;
        this.generos = generos;
    }

    // ═══════════════════ Clasificación ═══════════════════

    /** Estilo de un texto de género, o null si es general o no se reconoce. */
    public static Estilo deGenero(String genero) {
        String g = BibliotecaService.normalizar(genero).replace('/', ' ').replace('-', ' ');
        if (GENERAL.matcher(g).matches()) return null;
        for (Estilo x : ESTILOS) if (x.patron().matcher(g).matches()) return x;
        return null;
    }

    public static Optional<Estilo> porClave(String clave) {
        return Optional.ofNullable(POR_CLAVE.get(clave));
    }

    /** Busca un estilo por clave o por nombre ("bachata", "Bachata", "Reggaetón"); también por un género cualquiera. */
    public Optional<Estilo> buscar(String texto) {
        if (texto == null) return Optional.empty();
        String n = BibliotecaService.normalizar(texto);
        for (Estilo x : POR_CLAVE.values())
            if (x.clave().equals(n) || BibliotecaService.normalizar(x.nombre()).equals(n)) return Optional.of(x);
        Estilo g = deGenero(texto);
        if (g != null) return Optional.of(g);
        return GENERAL.matcher(n).matches() ? Optional.of(SIN_ESTILO) : Optional.empty();
    }

    private record Indice(List<CancionDTO> base, Map<Long, Estilo> porCancion, Map<String, List<CancionDTO>> porEstilo) {}
    private volatile Indice indice;

    /** Se recalcula solo cuando cambia la biblioteca (o los géneros de los artistas, cada 10 minutos). */
    private Indice indice() {
        List<CancionDTO> todas = biblioteca.todas();
        Indice i = indice;
        if (i != null && i.base() == todas && System.currentTimeMillis() - creado < 10 * 60_000) return i;
        synchronized (this) {
            if (indice != null && indice.base() == todas && System.currentTimeMillis() - creado < 10 * 60_000) return indice;
            indice = construir(todas);
            creado = System.currentTimeMillis();
            return indice;
        }
    }
    private volatile long creado;

    private Indice construir(List<CancionDTO> todas) {
        Map<Long, Estilo> porCancion = new HashMap<>();
        Map<String, Map<String, Integer>> votosArtista = new HashMap<>();
        List<CancionDTO> pendientes = new ArrayList<>();
        // 1) Por el propio género
        for (CancionDTO c : todas) {
            Estilo x = deGenero(c.getGenero());
            if (x != null) {
                porCancion.put(c.getId(), x);
                votosArtista.computeIfAbsent(GeneroService.clave(c.getArtista()), k -> new HashMap<>()).merge(x.clave(), 1, Integer::sum);
            } else pendientes.add(c);
        }
        // 2) Por los géneros del artista (MusicBrainz) y 3) por sus otras canciones
        Map<String, Estilo> cacheArtista = new HashMap<>();
        for (CancionDTO c : pendientes) {
            String k = GeneroService.clave(c.getArtista());
            Estilo x = cacheArtista.computeIfAbsent(k, a -> {
                for (String g : generos.generosDe(c.getArtista())) {
                    Estilo s = deGenero(g);
                    if (s != null) return s;
                }
                Map<String, Integer> v = votosArtista.get(a);
                if (v != null && !v.isEmpty()) return POR_CLAVE.get(Collections.max(v.entrySet(), Map.Entry.comparingByValue()).getKey());
                return SIN_ESTILO;
            });
            porCancion.put(c.getId(), x);
        }
        Map<String, List<CancionDTO>> porEstilo = new LinkedHashMap<>();
        for (CancionDTO c : todas) porEstilo.computeIfAbsent(porCancion.get(c.getId()).clave(), k -> new ArrayList<>()).add(c);
        return new Indice(todas, porCancion, porEstilo);
    }

    public Estilo de(CancionDTO c) {
        if (c == null) return SIN_ESTILO;
        Estilo x = indice().porCancion().get(c.getId());
        return x != null ? x : Optional.ofNullable(deGenero(c.getGenero())).orElse(SIN_ESTILO);
    }

    public List<CancionDTO> canciones(String clave) {
        return indice().porEstilo().getOrDefault(clave, List.of());
    }

    /** Estilos con sus canciones, de más a menos (para Explorar). */
    public List<Map<String, Object>> estilos(Predicate<Estilo> filtro) {
        List<Map<String, Object>> r = new ArrayList<>();
        indice().porEstilo().forEach((k, cs) -> {
            Estilo x = POR_CLAVE.get(k);
            if (x == null || cs.size() < 3 || !filtro.test(x)) return;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("nombre", x.nombre());
            m.put("clave", x.clave());
            m.put("totalCanciones", cs.size());
            m.put("reproducciones", cs.stream().mapToInt(CancionDTO::getReproducciones).sum());
            m.put("portadaUrl", cs.stream().filter(c -> c.getPortadaUrl() != null)
                    .max(Comparator.comparingInt(CancionDTO::getReproducciones)).map(CancionDTO::getPortadaUrl).orElse(null));
            r.add(m);
        });
        r.sort(Comparator.comparingInt((Map<String, Object> m) -> (int) m.get("reproducciones") * 3 + (int) m.get("totalCanciones")).reversed());
        return r;
    }

    /** "Aparte" (cristiana, infantil): solo se recomienda a quien la escucha. */
    public static boolean esAparte(Estilo x) {
        return GeneroService.APARTE.contains(x.familia());
    }

    // ═══════════════════ Página de un estilo ═══════════════════

    /**
     * Página de un género ordenada en secciones cortas (como Spotify): lo esencial, lo nuevo, clásicos,
     * recién llegadas y lo que aún no escuchas. Nunca devuelve las mil canciones de golpe.
     */
    public Map<String, Object> pagina(Estilo x, Set<Long> escuchadas) {
        List<CancionDTO> cs = canciones(x.clave());
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("nombre", x.nombre());
        r.put("clave", x.clave());
        r.put("total", cs.size());
        List<CancionDTO> esenciales = fluido(variadas(porPopularidad(cs), 2, 30));
        r.put("canciones", esenciales); // compatibilidad: versiones anteriores de la app muestran esta lista
        List<Map<String, Object>> secciones = new ArrayList<>();
        Set<Long> usadas = esenciales.stream().map(CancionDTO::getId).collect(Collectors.toSet());
        int anio = Year.now().getValue();
        seccion(secciones, usadas, "nuevo", "Lo nuevo", "De los últimos años",
                cs.stream().filter(c -> c.getAnio() != null && c.getAnio() >= anio - 2).toList(), 2, 20);
        seccion(secciones, usadas, "clasicos", "Clásicos", "Antes de " + (anio - 8),
                cs.stream().filter(c -> c.getAnio() != null && c.getAnio() < anio - 8).toList(), 2, 20);
        seccion(secciones, usadas, "recientes", "Recién llegadas", "Lo último que se agregó al servidor",
                cs.stream().filter(c -> c.getFechaAgregada() != null)
                        .sorted(Comparator.comparing(CancionDTO::getFechaAgregada).reversed()).limit(60).toList(), 2, 20);
        if (escuchadas != null)
            seccion(secciones, usadas, "descubrir", "Aún no las escuchas", "Del mismo estilo, nuevas para ti",
                    barajar(cs.stream().filter(c -> !escuchadas.contains(c.getId())).toList(), x.clave().hashCode() + LocalDateTime.now().getDayOfYear()), 1, 20);
        r.put("secciones", secciones);
        // Artistas del estilo, por cuánto se escuchan
        Map<String, List<CancionDTO>> porArtista = new LinkedHashMap<>();
        for (CancionDTO c : cs) porArtista.computeIfAbsent(GeneroService.clave(c.getArtista()), k -> new ArrayList<>()).add(c);
        r.put("artistas", porArtista.values().stream()
                .sorted(Comparator.comparingInt((List<CancionDTO> l) -> l.stream().mapToInt(CancionDTO::getReproducciones).sum() * 2 + l.size()).reversed())
                .limit(15).map(l -> {
                    CancionDTO c = l.get(0);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("nombre", GeneroService.principal(c.getArtista()));
                    m.put("portadaUrl", l.stream().map(CancionDTO::getPortadaUrl).filter(Objects::nonNull).findFirst().orElse(null));
                    m.put("totalCanciones", l.size());
                    return m;
                }).toList());
        return r;
    }

    private void seccion(List<Map<String, Object>> secciones, Set<Long> usadas, String clave, String titulo, String sub,
                         List<CancionDTO> pool, int porArtista, int max) {
        List<CancionDTO> l = variadas(pool.stream().filter(c -> !usadas.contains(c.getId())).toList(), porArtista, max);
        if (l.size() < 6) return;
        l.forEach(c -> usadas.add(c.getId()));
        secciones.add(Map.of("clave", clave, "titulo", titulo, "sub", sub, "canciones", fluido(l)));
    }

    /** Todas las canciones de un estilo por páginas, en orden variado (no una tras otra del mismo álbum). */
    public Map<String, Object> todas(Estilo x, int pagina, int tam) {
        List<CancionDTO> orden = variadas(porPopularidad(canciones(x.clave())), Integer.MAX_VALUE, Integer.MAX_VALUE);
        int desde = Math.min(orden.size(), pagina * tam);
        return Map.of("canciones", orden.subList(desde, Math.min(orden.size(), desde + tam)),
                "pagina", pagina, "total", orden.size(), "hayMas", desde + tam < orden.size());
    }

    // ═══════════════════ Orden inteligente ═══════════════════

    private static List<CancionDTO> porPopularidad(List<CancionDTO> cs) {
        return cs.stream().sorted(Comparator.comparingInt(CancionDTO::getReproducciones).reversed()
                .thenComparing(c -> c.getFechaAgregada() == null ? LocalDateTime.MIN : c.getFechaAgregada(), Comparator.reverseOrder())).toList();
    }

    private static List<CancionDTO> barajar(List<CancionDTO> cs, long semilla) {
        List<CancionDTO> l = new ArrayList<>(cs);
        Collections.shuffle(l, new Random(semilla));
        return l;
    }

    /**
     * Elige respetando el orden dado, pero con variedad: máximo {@code porArtista} canciones de cada artista
     * por "vuelta" y una sola canción por álbum por vuelta. Las demás quedan para vueltas siguientes.
     */
    public static List<CancionDTO> variadas(List<CancionDTO> cs, int porArtista, int max) {
        List<CancionDTO> out = new ArrayList<>();
        List<CancionDTO> resto = new ArrayList<>(cs);
        while (!resto.isEmpty() && out.size() < max) {
            Map<String, Integer> art = new HashMap<>();
            Set<String> albumes = new HashSet<>();
            List<CancionDTO> siguiente = new ArrayList<>();
            for (CancionDTO c : resto) {
                if (out.size() >= max) break;
                String a = GeneroService.clave(c.getArtista());
                String al = a + "|" + BibliotecaService.normalizar(c.getAlbum());
                boolean single = "single".equals(BibliotecaService.normalizar(c.getAlbum()));
                if (art.getOrDefault(a, 0) >= Math.min(porArtista, 3) || (!single && !albumes.add(al))) { siguiente.add(c); continue; }
                art.merge(a, 1, Integer::sum);
                out.add(c);
            }
            if (siguiente.size() == resto.size()) { // no se pudo avanzar: se agregan tal cual
                for (CancionDTO c : siguiente) { if (out.size() >= max) break; out.add(c); }
                break;
            }
            resto = siguiente;
            if (porArtista != Integer.MAX_VALUE && out.size() >= max) break;
        }
        return out;
    }

    /** Qué tan bien suena B después de A (más alto = mejor transición). */
    public double transicion(CancionDTO a, CancionDTO b) {
        Estilo ea = de(a), eb = de(b);
        double s = 0;
        if (ea.clave().equals(eb.clave())) s += 3;
        else if (ea.familia().equals(eb.familia())) s += 1.5;
        else s -= 1.5;
        if (a.getAnio() != null && b.getAnio() != null) s += Math.max(0, 1 - Math.abs(a.getAnio() - b.getAnio()) / 6.0);
        if (GeneroService.clave(a.getArtista()).equals(GeneroService.clave(b.getArtista()))) s -= 4; // no seguidos
        return s;
    }

    /**
     * Ordena una lista para que fluya: cada canción va seguida de la que mejor encaja entre las próximas
     * (se respeta más o menos el orden original: solo mira 8 posiciones adelante).
     */
    public List<CancionDTO> fluido(List<CancionDTO> lista) {
        if (lista.size() < 3) return lista;
        List<CancionDTO> resto = new ArrayList<>(lista);
        List<CancionDTO> out = new ArrayList<>();
        out.add(resto.remove(0));
        while (!resto.isEmpty()) {
            CancionDTO prev = out.get(out.size() - 1);
            int mejor = 0;
            double puntos = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < Math.min(8, resto.size()); i++) {
                double p = transicion(prev, resto.get(i)) - i * 0.35; // preferir lo que ya venía antes
                if (p > puntos) { puntos = p; mejor = i; }
            }
            out.add(resto.remove(mejor));
        }
        return out;
    }

    /**
     * Agrupa una mezcla por estilos (bloques) en vez de revolverlos: los estilos con más peso primero,
     * y dentro de cada bloque, orden fluido. Así un mix pasa de bachata a bachata, luego a salsa…
     */
    public List<CancionDTO> enBloques(List<CancionDTO> lista) {
        Map<String, List<CancionDTO>> porFamilia = new LinkedHashMap<>();
        for (CancionDTO c : lista) porFamilia.computeIfAbsent(de(c).familia(), k -> new ArrayList<>()).add(c);
        List<CancionDTO> out = new ArrayList<>();
        porFamilia.values().stream().sorted(Comparator.comparingInt((List<CancionDTO> l) -> l.size()).reversed())
                .forEach(l -> {
                    // dentro de la familia, juntos por estilo exacto
                    Map<String, List<CancionDTO>> porEstilo = new LinkedHashMap<>();
                    for (CancionDTO c : l) porEstilo.computeIfAbsent(de(c).clave(), k -> new ArrayList<>()).add(c);
                    porEstilo.values().forEach(b -> out.addAll(fluido(RecomendacionService.separarArtistas(b, null))));
                });
        return RecomendacionService.separarArtistas(fluido(out), null);
    }

    /** Estilos que más escucha alguien (clave → peso), a partir de sus canciones con peso. */
    public Map<String, Double> pesos(Map<Long, ? extends Number> cancionesConPeso) {
        Map<String, Double> r = new HashMap<>();
        cancionesConPeso.forEach((id, w) -> biblioteca.porId(id).ifPresent(c -> r.merge(de(c).clave(), w.doubleValue(), Double::sum)));
        return r;
    }
}
