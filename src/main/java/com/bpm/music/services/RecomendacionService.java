package com.bpm.music.services;

import com.bpm.music.dtos.ArtistaDTO;
import com.bpm.music.dtos.CancionDTO;
import com.bpm.music.repositories.HistorialReproduccionRepository;
import com.bpm.music.repositories.UsuarioReproduccionRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static com.bpm.music.services.BibliotecaService.normalizar;

/**
 * Radio, mezclas y recomendaciones. Trabaja sobre la biblioteca en memoria.
 */
@Service
public class RecomendacionService {

    private final BibliotecaService biblioteca;
    private final UsuarioService usuarios;
    private final HistorialReproduccionRepository historialRepo;
    private final UsuarioReproduccionRepository reproRepo;
    private final GeneroService generos;

    public RecomendacionService(BibliotecaService biblioteca, UsuarioService usuarios,
                                HistorialReproduccionRepository historialRepo,
                                UsuarioReproduccionRepository reproRepo, GeneroService generos) {
        this.generos = generos;
        this.biblioteca = biblioteca;
        this.usuarios = usuarios;
        this.historialRepo = historialRepo;
        this.reproRepo = reproRepo;
    }

    private EstilosService estilos;
    private com.bpm.music.organizador.SenalesExternasService senales;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSenales(com.bpm.music.organizador.SenalesExternasService senales) { this.senales = senales; }

    @org.springframework.beans.factory.annotation.Autowired
    void setEstilos(EstilosService estilos) { this.estilos = estilos; }

    // ═══════════════════════ Radio ═══════════════════════

    /** Lista de canciones parecidas a una dada (ver {@link #radio(List, int, Long, Collection)}). */
    public List<CancionDTO> radio(Long semillaId, int cantidad, Long userId, Collection<Long> excluirExtra) {
        return radio(List.of(semillaId), cantidad, userId, excluirExtra);
    }

    /**
     * Radio: canciones parecidas a una o varias canciones (las últimas que sonaron).
     * Puntúa cada canción por:
     *  - mismo artista, y artistas con géneros parecidos (MusicBrainz),
     *  - "quienes escuchan a este artista también escuchan…" (todos los usuarios del servidor),
     *  - mismo género o familia de género, época parecida,
     *  - gustos del usuario y popularidad.
     * Excluye lo escuchado hace poco y reparte los artistas para que no se repitan seguidos.
     */
    public List<CancionDTO> radio(List<Long> semillasIds, int cantidad, Long userId, Collection<Long> excluirExtra) {
        List<CancionDTO> semillas = semillasIds.stream().limit(4).map(biblioteca::porId)
                .flatMap(Optional::stream).toList();
        if (semillas.isEmpty()) throw new NoSuchElementException("Canción no encontrada");
        CancionDTO principal = semillas.get(0);
        // Si suena algo normal, la radio no cambia a música aparte (cristiana, infantil) que la persona no escucha
        var permitidasRadio = semillas.stream().anyMatch(x -> GeneroService.esAparte(x.getGenero()))
                ? (java.util.function.Predicate<CancionDTO>) x -> true : permitidas(userId);
        Set<Long> excluir = new HashSet<>(recientesSesion(userId, 25));
        semillas.forEach(x -> excluir.add(x.getId()));
        if (excluirExtra != null) excluir.addAll(excluirExtra);

        Map<String, Long> gustos = userId == null ? Map.of() : usuarios.pesosArtistas(userId);
        long maxGusto = gustos.values().stream().mapToLong(Long::longValue).max().orElse(1);
        Map<String, Map<String, Double>> coescucha = coescucha();
        Random rnd = new Random();
        int maxRepros = Math.max(1, biblioteca.todas().stream().mapToInt(CancionDTO::getReproducciones).max().orElse(1));
        Map<String, Double> parecidoArtista = new HashMap<>();

        record Cand(CancionDTO c, double score) {}
        List<Cand> cands = new ArrayList<>();
        for (CancionDTO c : biblioteca.todas()) {
            if (excluir.contains(c.getId())) continue;
            if (!permitidasRadio.test(c)) continue;
            String a = normalizar(MetadataService.primerArtista(c.getArtista()));
            String g = normalizar(c.getGenero());
            double s = 0;
            for (int i = 0; i < semillas.size(); i++) {
                CancionDTO sem = semillas.get(i);
                double peso = i == 0 ? 1.0 : 0.6; // la canción actual pesa más que las anteriores
                String sa = normalizar(MetadataService.primerArtista(sem.getArtista()));
                String sg = normalizar(sem.getGenero());
                double p = 0;
                if (a.equals(sa)) p += 7;
                else {
                    p += 9 * parecidoArtista.computeIfAbsent(sa + "|" + a, k -> generos.parecido(sem.getArtista(), c.getArtista()));
                    p += 6 * coescucha.getOrDefault(sa, Map.of()).getOrDefault(a, 0.0);
                }
                // Estilo concreto (bachata, reggaetón…), no el texto del género: "Latino" junta demasiado
                EstilosService.Estilo ec = estilos.de(c), es = estilos.de(sem);
                boolean conocidos = ec != EstilosService.SIN_ESTILO && es != EstilosService.SIN_ESTILO;
                if (conocidos && ec.clave().equals(es.clave())) p += 5;
                else if (ec.familia().equals(es.familia())) p += 2.5;
                else if (conocidos) p -= 2; // saltar de estilo sin relación
                else if (!g.isEmpty() && g.equals(sg)) p += 1;
                if (!"single".equals(normalizar(sem.getAlbum())) && normalizar(c.getAlbum()).equals(normalizar(sem.getAlbum()))) p += 1.5;
                if (c.getAnio() != null && sem.getAnio() != null && Math.abs(c.getAnio() - sem.getAnio()) <= 5) p += 1.2;
                // "Canciones relacionadas" de YouTube Music que ya están en el servidor (dato guardado: sin llamadas aquí)
                if (senales != null) p += 7 * senales.peso(sem.getId(), c.getId());
                s += peso * p;
            }
            s /= (1 + 0.6 * (semillas.size() - 1));
            Long gusto = gustos.get(a);
            if (gusto != null) s += 3.5 * gusto / maxGusto;
            s += 1.5 * c.getReproducciones() / maxRepros;
            if (s <= 0.8) continue;
            cands.add(new Cand(c, s + rnd.nextDouble() * 2.5)); // un poco de variedad
        }
        if (cands.size() < cantidad) {
            // Biblioteca pequeña o sin géneros: completar con aleatorias
            List<CancionDTO> resto = new ArrayList<>(biblioteca.todas());
            Collections.shuffle(resto);
            Set<Long> ya = cands.stream().map(x -> x.c().getId()).collect(Collectors.toSet());
            for (CancionDTO c : resto) {
                if (cands.size() >= cantidad * 2) break;
                if (!excluir.contains(c.getId()) && permitidasRadio.test(c) && ya.add(c.getId())) cands.add(new Cand(c, rnd.nextDouble()));
            }
        }
        cands.sort(Comparator.comparingDouble(Cand::score).reversed());
        List<CancionDTO> elegidas = cands.stream().limit(Math.max(cantidad * 2L, 30)).map(Cand::c).collect(Collectors.toList());
        // Limitar el mismo artista a ~1/3 de la lista y no repetirlo seguido
        List<CancionDTO> r = separarArtistas(limitarPorArtista(elegidas, Math.max(2, cantidad / 3)), principal.getArtista())
                .stream().limit(cantidad).toList();
        return estilos.fluido(r);
    }

    /**
     * "Quienes escuchan a A también escuchan a B": para cada par de artistas, cuántos usuarios
     * escucharon a los dos (normalizado de 0 a 1). Se recalcula cada 15 minutos.
     */
    private volatile Map<String, Map<String, Double>> coescuchaCache = Map.of();
    private volatile long coescuchaT = 0;

    private Map<String, Map<String, Double>> coescucha() {
        if (System.currentTimeMillis() - coescuchaT < 15 * 60_000) return coescuchaCache;
        Map<Long, Set<String>> porUsuario = new HashMap<>();
        for (Object[] r : reproRepo.findConteoGlobal(java.time.LocalDateTime.now().minusDays(120))) {
            Long uid = ((Number) r[0]).longValue();
            biblioteca.porId(((Number) r[1]).longValue()).ifPresent(c ->
                    porUsuario.computeIfAbsent(uid, k -> new HashSet<>()).add(normalizar(MetadataService.primerArtista(c.getArtista()))));
        }
        Map<String, Integer> oyentes = new HashMap<>();
        Map<String, Map<String, Integer>> pares = new HashMap<>();
        for (Set<String> arts : porUsuario.values()) {
            for (String x : arts) {
                oyentes.merge(x, 1, Integer::sum);
                for (String y : arts) if (!x.equals(y)) pares.computeIfAbsent(x, k -> new HashMap<>()).merge(y, 1, Integer::sum);
            }
        }
        Map<String, Map<String, Double>> res = new HashMap<>();
        pares.forEach((x, m) -> {
            Map<String, Double> n = new HashMap<>();
            m.forEach((y, c) -> n.put(y, c / Math.sqrt((double) oyentes.get(x) * oyentes.get(y))));
            res.put(x, n);
        });
        coescuchaCache = res;
        coescuchaT = System.currentTimeMillis();
        return res;
    }

    /** Siguiente canción recomendada (radio automática al terminar una lista). */
    public CancionDTO siguiente(Long actualId, Long userId) {
        List<CancionDTO> r = radio(actualId, 5, userId, null);
        if (!r.isEmpty()) return r.get(0);
        List<CancionDTO> todas = biblioteca.todas();
        if (todas.isEmpty()) throw new NoSuchElementException("La biblioteca está vacía");
        return todas.get(new Random().nextInt(todas.size()));
    }

    // ═══════════════════════ Mixes ═══════════════════════

    public List<CancionDTO> mixAleatorio(int cantidad, String genero, String artista) {
        List<CancionDTO> pool;
        if (artista != null && !artista.isBlank()) pool = biblioteca.cancionesDeArtista(artista);
        else if (genero != null && !genero.isBlank()) {
            // Por estilo concreto; si no se reconoce, por el texto del género
            pool = estilos.buscar(genero).map(x -> estilos.canciones(x.clave())).filter(l -> !l.isEmpty())
                    .orElse(biblioteca.cancionesDeGenero(genero));
        } else pool = biblioteca.todas();
        Random rnd = new Random();
        // Variado (pocos por artista) y en orden que fluye, no 30 canciones al azar de golpe
        List<CancionDTO> elegidas = ponderado(pool, rnd, Math.min(pool.size(), cantidad * 3));
        return estilos.fluido(EstilosService.variadas(elegidas, Math.max(2, cantidad / 8), cantidad));
    }

    public List<CancionDTO> mixArtista(String artista, int cantidad) {
        List<CancionDTO> delArtista = biblioteca.cancionesDeArtista(artista);
        if (delArtista.isEmpty()) return List.of();
        // Estilos del artista (los suyos de verdad, no "Latino")
        Map<String, Long> suyos = delArtista.stream().collect(Collectors.groupingBy(c -> estilos.de(c).clave(), Collectors.counting()));
        String clave = normalizar(artista);
        Map<String, Double> parecidos = new HashMap<>();
        List<CancionDTO> similares = biblioteca.todas().stream()
                .filter(c -> !normalizar(c.getArtista()).equals(clave))
                .filter(c -> suyos.containsKey(estilos.de(c).clave())
                        || parecidos.computeIfAbsent(normalizar(c.getArtista()), a -> generos.parecido(artista, c.getArtista())) >= 0.5)
                .collect(Collectors.toList());
        Random rnd = new Random();
        int cuota = similares.isEmpty() ? cantidad : (int) Math.ceil(cantidad * 0.5);
        List<CancionDTO> out = new ArrayList<>(ponderado(delArtista, rnd, cuota));
        out.addAll(EstilosService.variadas(ponderado(similares, rnd, cantidad * 2), 2, cantidad - out.size()));
        Collections.shuffle(out, rnd);
        return estilos.fluido(separarArtistas(sinDuplicados(out), null));
    }

    /**
     * Cuánto escucha alguien cada estilo (clave → peso): sus reproducciones de 90 días, sus Me gusta
     * y los géneros que eligió al crear la cuenta. Vacío si todavía no hay datos.
     */
    public Map<String, Double> pesosEstilo(Long userId) {
        if (userId == null) return Map.of();
        Map<Long, Double> porCancion = new HashMap<>();
        for (Object[] r : reproRepo.findConteoPorCancion(userId, LocalDateTime.now().minusDays(90)))
            porCancion.merge(((Number) r[0]).longValue(), ((Number) r[1]).doubleValue(), Double::sum);
        for (CancionDTO c : usuarios.favoritos(userId)) porCancion.merge(c.getId(), 3.0, Double::sum);
        Map<String, Double> pesos = new HashMap<>(estilos.pesos(porCancion));
        if (gustos != null) for (var g : gustos.findByUsuarioId(userId)) {
            if (g.getTipo() != com.bpm.music.comunidad.UsuarioGusto.Tipo.GENERO) continue;
            estilos.buscar(g.getValor()).ifPresent(x -> pesos.merge(x.clave(), 8.0, Double::sum));
        }
        pesos.remove(EstilosService.SIN_ESTILO.clave()); // "Latino variado" no dice nada de su gusto
        return pesos;
    }

    /** Los estilos favoritos de alguien (o los más escuchados del servidor si aún no hay datos). */
    private List<String> estilosFavoritos(Long userId, int n) {
        Map<String, Double> p = pesosEstilo(userId);
        var permitidas = permitidas(userId);
        if (p.isEmpty()) {
            return estilos.estilos(x -> !EstilosService.esAparte(x) && x != EstilosService.SIN_ESTILO).stream()
                    .limit(n).map(m -> (String) m.get("clave")).toList();
        }
        return p.entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .filter(k -> estilos.canciones(k).stream().anyMatch(permitidas))
                .limit(n).toList();
    }

    // ═══════════════════════ Música "aparte" (cristiana, infantil) ═══════════════════════

    private com.bpm.music.comunidad.UsuarioGustoRepository gustos;

    @org.springframework.beans.factory.annotation.Autowired
    void setGustos(com.bpm.music.comunidad.UsuarioGustoRepository gustos) {
        this.gustos = gustos;
        com.bpm.music.comunidad.ComunidadService.alCambiarGustos.add(uid -> { cacheMixes.borrar(uid); cacheAparte.borrar(uid); });
    }

    private final CacheTemporal<Long, Set<String>> cacheAparte = new CacheTemporal<>(5 * 60_000, 500);

    /** Familias aparte que esta persona sí escucha (3 o más canciones, o el 10 % de lo que escucha y le gusta). */
    public Set<String> apartesQueLeGustan(Long userId) {
        if (userId == null) return Set.of();
        return cacheAparte.obtener(userId, () -> {
            List<CancionDTO> suyas = new ArrayList<>(usuarios.masEscuchadas(userId, 365, 300));
            suyas.addAll(usuarios.favoritos(userId));
            Map<String, Integer> n = new HashMap<>();
            for (CancionDTO c : suyas) {
                String f = GeneroService.familia(c.getGenero());
                if (GeneroService.APARTE.contains(f)) n.merge(f, 1, Integer::sum);
            }
            int total = Math.max(1, suyas.size());
            Set<String> r = new HashSet<>();
            n.forEach((f, k) -> { if (k >= 3 || k * 10 >= total) r.add(f); });
            // También si la eligió entre sus gustos al crear la cuenta
            if (gustos != null) for (var g : gustos.findByUsuarioId(userId)) {
                if (g.getTipo() != com.bpm.music.comunidad.UsuarioGusto.Tipo.GENERO) continue;
                String f = GeneroService.familia(g.getValor());
                if (GeneroService.APARTE.contains(f)) r.add(f);
            }
            return r;
        });
    }

    /** ¿Se le puede recomendar esta canción a esta persona? */
    public java.util.function.Predicate<CancionDTO> permitidas(Long userId) {
        Set<String> ok = apartesQueLeGustan(userId);
        return c -> {
            String f = GeneroService.familia(c.getGenero());
            if (GeneroService.APARTE.contains(f)) return ok.contains(f);
            String fe = estilos.de(c).familia(); // p. ej. "Latino" de un artista cristiano
            return !GeneroService.APARTE.contains(fe) || ok.contains(fe);
        };
    }

    /** Un artista es "aparte" si la mayoría de sus canciones lo son. */
    public boolean artistaPermitido(String artista, java.util.function.Predicate<CancionDTO> permitidas) {
        List<CancionDTO> suyas = biblioteca.cancionesDeArtista(artista);
        if (suyas.isEmpty()) return true;
        long no = suyas.stream().filter(permitidas.negate()).count();
        return no * 2 < suyas.size();
    }

    /**
     * Mix del día: cambia cada día y es distinto para cada usuario. Se arma con sus 3 estilos favoritos
     * (en proporción a cuánto los escucha) y va por bloques: no salta de bachata a hip hop y de vuelta.
     */
    public List<CancionDTO> mixDiario(Long userId, int cantidad) {
        long semilla = LocalDate.now().toEpochDay() * 31 + (userId == null ? 0 : userId * 7919);
        Random rnd = new Random(semilla);
        List<CancionDTO> todas = biblioteca.todas();
        if (todas.isEmpty()) return List.of();
        if (userId == null) return estilos.enBloques(EstilosService.variadas(ponderado(todas, rnd, cantidad * 3), 2, cantidad));
        var permitidas = permitidas(userId);
        Map<String, Long> gustos = usuarios.pesosArtistas(userId);
        Set<Long> recientes = new HashSet<>(recientesSesion(userId, 40));
        Map<String, Double> pesos = pesosEstilo(userId);
        List<String> favoritos = estilosFavoritos(userId, 3);
        double total = favoritos.stream().mapToDouble(k -> pesos.getOrDefault(k, 1.0)).sum();

        List<CancionDTO> out = new ArrayList<>();
        for (String k : favoritos) {
            int cuota = (int) Math.round(cantidad * 0.85 * pesos.getOrDefault(k, 1.0) / Math.max(total, 1e-9));
            List<CancionDTO> pool = estilos.canciones(k).stream().filter(permitidas).filter(c -> !recientes.contains(c.getId())).toList();
            // Primero sus artistas, luego otros del mismo estilo
            List<CancionDTO> deSusArtistas = pool.stream().filter(c -> gustos.containsKey(normalizar(c.getArtista()))).toList();
            List<CancionDTO> bloque = new ArrayList<>(ponderado(deSusArtistas, rnd, (int) Math.ceil(cuota * 0.55)));
            bloque.addAll(ponderado(pool.stream().filter(c -> !bloque.contains(c)).toList(), rnd, cuota - bloque.size()));
            out.addAll(EstilosService.variadas(bloque, 2, cuota));
        }
        // Un toque de algo nuevo de la misma familia, y relleno si faltan canciones
        Set<String> familias = favoritos.stream().map(k -> EstilosService.porClave(k).map(EstilosService.Estilo::familia).orElse("")).collect(Collectors.toSet());
        List<CancionDTO> cercanas = todas.stream().filter(permitidas).filter(c -> !recientes.contains(c.getId()))
                .filter(c -> familias.contains(estilos.de(c).familia())).toList();
        out.addAll(ponderado(cercanas, rnd, cantidad - out.size()));
        out = sinDuplicados(out);
        if (out.size() < cantidad) out.addAll(ponderado(todas.stream().filter(permitidas).toList(), rnd, cantidad - out.size()));
        return estilos.enBloques(sinDuplicados(out).stream().limit(cantidad).collect(Collectors.toList()));
    }

    /** Canciones que el usuario nunca ha escuchado, de sus estilos favoritos y parecidos, por bloques. */
    public List<CancionDTO> descubrir(Long userId, int cantidad) {
        Set<Long> escuchadas = escuchadas(userId);
        long semilla = LocalDate.now().toEpochDay() / 7 * 13 + userId;
        Random rnd = new Random(semilla);
        var permitidas = permitidas(userId);
        List<CancionDTO> nuevas = biblioteca.todas().stream().filter(c -> !escuchadas.contains(c.getId()))
                .filter(permitidas).collect(Collectors.toList());
        List<String> favoritos = estilosFavoritos(userId, 4);
        Set<String> familias = favoritos.stream().map(k -> EstilosService.porClave(k).map(EstilosService.Estilo::familia).orElse("")).collect(Collectors.toSet());
        List<CancionDTO> afines = nuevas.stream().filter(c -> favoritos.contains(estilos.de(c).clave())).collect(Collectors.toList());
        List<CancionDTO> cercanas = nuevas.stream().filter(c -> !favoritos.contains(estilos.de(c).clave()) && familias.contains(estilos.de(c).familia())).collect(Collectors.toList());
        Collections.shuffle(afines, rnd);
        Collections.shuffle(cercanas, rnd);
        Collections.shuffle(nuevas, rnd);
        List<CancionDTO> out = new ArrayList<>(EstilosService.variadas(afines, 2, cantidad * 6 / 10));
        out.addAll(EstilosService.variadas(cercanas, 2, cantidad * 3 / 10));
        out.addAll(EstilosService.variadas(nuevas, 1, cantidad));
        return estilos.enBloques(sinDuplicados(out).stream().limit(cantidad).collect(Collectors.toList()));
    }

    /** Canciones que la persona escuchó alguna vez. */
    public Set<Long> escuchadas(Long userId) {
        if (userId == null) return Set.of();
        return reproRepo.findConteoPorCancion(userId, LocalDateTime.of(2000, 1, 1, 0, 0)).stream()
                .map(r -> ((Number) r[0]).longValue()).collect(Collectors.toSet());
    }

    /** Tarjetas de mezclas para la pantalla de inicio. */
    private final CacheTemporal<Long, List<Map<String, Object>>> cacheMixes = new CacheTemporal<>(5 * 60_000, 500);

    /** Tarjetas de mezclas (se recalculan cada 5 minutos como máximo por usuario). */
    public List<Map<String, Object>> mixesPara(Long userId) {
        return cacheMixes.obtener(userId, () -> calcularMixes(userId));
    }

    private List<Map<String, Object>> calcularMixes(Long userId) {
        List<Map<String, Object>> mixes = new ArrayList<>();
        if (biblioteca.total() == 0) return mixes;

        List<CancionDTO> diario = mixDiario(userId, 25);
        mixes.add(mix("diario", "Mix del día", "Una selección nueva cada día", diario));

        List<CancionDTO> desc = descubrir(userId, 30);
        if (desc.size() >= 5) mixes.add(mix("descubrir", "Descubrir", "Canciones que aún no has escuchado", desc));

        Map<String, Long> gustos = usuarios.pesosArtistas(userId);
        List<String> topArtistas = gustos.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(Map.Entry::getKey).limit(3).toList();
        var permitidas = permitidas(userId);
        if (topArtistas.isEmpty()) {
            topArtistas = biblioteca.artistas().stream().filter(a -> a.totalCanciones() >= 3)
                    .filter(a -> artistaPermitido(a.nombre(), permitidas))
                    .limit(3).map(a -> normalizar(a.nombre())).toList();
        }
        for (String clave : topArtistas) {
            Optional<ArtistaDTO> a = biblioteca.artistas().stream().filter(x -> normalizar(x.nombre()).equals(clave)).findFirst();
            if (a.isEmpty()) continue;
            List<CancionDTO> m = mixArtista(a.get().nombre(), 25);
            if (m.size() >= 4) mixes.add(mix("artista:" + a.get().nombre(), "Mix de " + a.get().nombre(),
                    "Con artistas parecidos", m));
        }
        for (String k : estilosFavoritos(userId, 4)) {
            EstilosService.Estilo x = EstilosService.porClave(k).orElse(null);
            if (x == null) continue;
            List<CancionDTO> m = mixAleatorio(25, x.clave(), null);
            if (m.size() >= 6) mixes.add(mix("genero:" + x.clave(), "Mix de " + x.nombre(), "Lo mejor de " + x.nombre(), m));
        }
        return mixes;
    }

    public List<CancionDTO> obtenerMix(String id, Long userId, int cantidad) {
        if (id == null) return List.of();
        if (id.equals("diario")) return mixDiario(userId, cantidad);
        if (id.equals("descubrir")) return descubrir(userId, cantidad);
        if (id.startsWith("artista:")) return mixArtista(id.substring(8), cantidad);
        if (id.startsWith("genero:")) return mixAleatorio(cantidad, id.substring(7), null);
        if (id.equals("aleatorio")) return mixAleatorio(cantidad, null, null);
        throw new NoSuchElementException("Mezcla no encontrada");
    }

    private Map<String, Object> mix(String id, String titulo, String subtitulo, List<CancionDTO> canciones) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("titulo", titulo);
        m.put("subtitulo", subtitulo);
        m.put("total", canciones.size());
        m.put("portadas", canciones.stream().map(CancionDTO::getPortadaUrl).distinct().limit(4).toList());
        m.put("artistas", canciones.stream().map(CancionDTO::getArtista).distinct().limit(3).toList());
        return m;
    }

    // ═══════════════════════ Historial ═══════════════════════

    private List<Long> recientesSesion(Long userId, int n) {
        if (userId == null) return List.of();
        return historialRepo.findCancionIdsRecientes(UsuarioService.sesionDe(userId), n).stream()
                .filter(Objects::nonNull).map(Number::longValue).toList();
    }

    @Scheduled(cron = "0 15 4 * * *")
    @Transactional
    public void limpiarHistorialAntiguo() {
        historialRepo.deleteOlderThan(LocalDateTime.now().minusDays(14));
    }

    // ═══════════════════════ Helpers ═══════════════════════

    /** Elige canciones al azar; las más escuchadas tienen algo más de probabilidad. */
    private static List<CancionDTO> ponderado(List<CancionDTO> pool, Random rnd, int cantidad) {
        if (pool.isEmpty() || cantidad <= 0) return new ArrayList<>();
        int max = Math.max(1, pool.stream().mapToInt(CancionDTO::getReproducciones).max().orElse(1));
        record P(CancionDTO c, double k) {}
        // Muestreo ponderado sin reemplazo (Efraimidis-Spirakis)
        return pool.stream()
                .map(c -> new P(c, Math.pow(rnd.nextDouble(), 1.0 / (1.0 + 2.0 * c.getReproducciones() / max))))
                .sorted(Comparator.comparingDouble(P::k).reversed())
                .limit(cantidad).map(P::c).collect(Collectors.toList());
    }

    private static List<CancionDTO> limitarPorArtista(List<CancionDTO> l, int max) {
        Map<String, Integer> cuenta = new HashMap<>();
        List<CancionDTO> out = new ArrayList<>();
        for (CancionDTO c : l) {
            if (cuenta.merge(normalizar(c.getArtista()), 1, Integer::sum) <= max) out.add(c);
        }
        return out;
    }

    private static List<CancionDTO> sinDuplicados(List<CancionDTO> l) {
        Set<Long> vistos = new HashSet<>();
        return l.stream().filter(c -> vistos.add(c.getId())).collect(Collectors.toList());
    }

    /** Reordena para que no suenen dos canciones seguidas del mismo artista. */
    static List<CancionDTO> separarArtistas(List<CancionDTO> lista, String artistaPrevio) {
        List<CancionDTO> pendientes = new ArrayList<>(lista);
        List<CancionDTO> out = new ArrayList<>(lista.size());
        String ultimo = artistaPrevio == null ? "" : normalizar(artistaPrevio);
        while (!pendientes.isEmpty()) {
            int idx = -1;
            for (int i = 0; i < pendientes.size(); i++) {
                if (!normalizar(pendientes.get(i).getArtista()).equals(ultimo)) { idx = i; break; }
            }
            if (idx < 0) idx = 0;
            CancionDTO c = pendientes.remove(idx);
            out.add(c);
            ultimo = normalizar(c.getArtista());
        }
        return out;
    }

    /** Soporte → Mantenimiento: vacía las mezclas y gustos calculados (se recalculan solos al pedirlos). */
    public void limpiarCaches() {
        cacheMixes.limpiar();
        cacheAparte.limpiar();
    }
}
