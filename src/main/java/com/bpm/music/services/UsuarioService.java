package com.bpm.music.services;

import com.bpm.music.dtos.CancionDTO;
import com.bpm.music.models.Cancion;
import com.bpm.music.models.HistorialReproduccion;
import com.bpm.music.models.Usuario;
import com.bpm.music.models.UsuarioFavorito;
import com.bpm.music.models.UsuarioReproduccion;
import com.bpm.music.repositories.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.format.TextStyle;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class UsuarioService {

    private final UsuarioRepository usuarioRepo;
    private final UsuarioFavoritoRepository favRepo;
    private final UsuarioReproduccionRepository reproRepo;
    private final HistorialReproduccionRepository historialRepo;
    private final CancionRepository cancionRepo;
    private final BibliotecaService biblioteca;

    public UsuarioService(UsuarioRepository usuarioRepo, UsuarioFavoritoRepository favRepo,
                          UsuarioReproduccionRepository reproRepo, HistorialReproduccionRepository historialRepo,
                          CancionRepository cancionRepo, BibliotecaService biblioteca) {
        this.usuarioRepo = usuarioRepo;
        this.favRepo = favRepo;
        this.reproRepo = reproRepo;
        this.historialRepo = historialRepo;
        this.cancionRepo = cancionRepo;
        this.biblioteca = biblioteca;
    }

    public static String sesionDe(Long userId) {
        return "u" + userId;
    }

    // ═══════════════════════ Favoritos ═══════════════════════

    public List<CancionDTO> favoritos(Long userId) {
        return biblioteca.porIds(favRepo.findCancionIdsByUsuario(userId));
    }

    public List<Long> favoritosIds(Long userId) {
        return favRepo.findCancionIdsByUsuario(userId);
    }

    @Transactional
    public Map<String, Object> toggleFavorito(Long userId, Long cancionId) {
        if (favRepo.existsByUsuarioIdAndCancionId(userId, cancionId)) {
            favRepo.borrar(userId, cancionId);
            return Map.of("favorito", false);
        }
        return agregarFavorito(userId, cancionId);
    }

    @Transactional
    public Map<String, Object> agregarFavorito(Long userId, Long cancionId) {
        biblioteca.requerir(cancionId);
        if (!favRepo.existsByUsuarioIdAndCancionId(userId, cancionId)) {
            favRepo.save(UsuarioFavorito.builder()
                    .usuario(usuarioRepo.getReferenceById(userId))
                    .cancion(cancionRepo.getReferenceById(cancionId))
                    .build());
        }
        return Map.of("favorito", true);
    }

    @Transactional
    public Map<String, Object> quitarFavorito(Long userId, Long cancionId) {
        favRepo.borrar(userId, cancionId);
        return Map.of("favorito", false);
    }

    // ═══════════════════════ Reproducciones ═══════════════════════

    /**
     * Registra que el usuario escuchó una canción. Se llama cuando lleva
     * al menos 30 s (o la mitad) para no contar las canciones saltadas.
     */
    @Transactional
    public void registrarReproduccion(Long userId, Long cancionId, String contexto, Integer segundos) {
        biblioteca.requerir(cancionId);
        Usuario u = usuarioRepo.getReferenceById(userId);
        Cancion c = cancionRepo.getReferenceById(cancionId);
        String ctx = contexto == null ? "otro" : contexto.replaceAll("[^A-Za-z0-9_-]", "");
        if (ctx.length() > 30) ctx = ctx.substring(0, 30);

        reproRepo.save(UsuarioReproduccion.builder()
                .usuario(u).cancion(c).contexto(ctx)
                .segundos(segundos != null && segundos > 0 ? Math.min(segundos, 24 * 3600) : null)
                .build());
        historialRepo.save(HistorialReproduccion.builder()
                .sesionId(sesionDe(userId)).cancion(c)
                .segundosEscuchados(segundos == null ? 0 : segundos)
                .fuente(ctx.toUpperCase(Locale.ROOT))
                .build());
        cancionRepo.incrementarReproduccion(cancionId);
        biblioteca.sumarReproduccion(cancionId);
    }

    /** Canciones escuchadas recientemente (sin repetir). */
    public List<CancionDTO> recientes(Long userId, int limite) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (Object[] fila : reproRepo.findRecientes(userId, PageRequest.of(0, limite * 4))) {
            ids.add(((Number) fila[0]).longValue());
            if (ids.size() >= limite) break;
        }
        return biblioteca.porIds(ids);
    }

    /** Lo más escuchado por el usuario en los últimos días. */
    public List<CancionDTO> masEscuchadas(Long userId, int dias, int limite) {
        LocalDateTime desde = LocalDateTime.now().minusDays(dias);
        List<Long> ids = reproRepo.findConteoPorCancion(userId, desde).stream()
                .limit(limite).map(r -> ((Number) r[0]).longValue()).toList();
        return biblioteca.porIds(ids);
    }

    // ═══════════════════════ Estadísticas ═══════════════════════

    public Map<String, Object> estadisticas(Long userId, int dias) {
        LocalDateTime desde = dias > 0 ? LocalDateTime.now().minusDays(dias) : LocalDateTime.of(2000, 1, 1, 0, 0);
        Map<String, Object> s = new LinkedHashMap<>();

        List<Object[]> conteo = reproRepo.findConteoPorCancion(userId, desde);
        long total = conteo.stream().mapToLong(r -> ((Number) r[1]).longValue()).sum();
        long segundos = Optional.ofNullable(reproRepo.sumSegundos(userId, desde)).orElse(0L);

        s.put("periodoDias", dias);
        s.put("totalReproducciones", total);
        s.put("cancionesDistintas", conteo.size());
        s.put("totalFavoritos", favRepo.countByUsuarioId(userId));
        s.put("segundosEscuchados", segundos);
        s.put("tiempoEscuchado", formatear(segundos));

        // Top canciones
        List<Map<String, Object>> topCanciones = new ArrayList<>();
        Map<String, Long> porArtista = new HashMap<>();
        Map<String, Long> porGenero = new HashMap<>();
        Map<String, String> nombreArtista = new HashMap<>();
        for (Object[] r : conteo) {
            Long id = ((Number) r[0]).longValue();
            long veces = ((Number) r[1]).longValue();
            Optional<CancionDTO> oc = biblioteca.porId(id);
            if (oc.isEmpty()) continue;
            CancionDTO c = oc.get();
            if (topCanciones.size() < 10) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("cancion", c);
                m.put("veces", veces);
                topCanciones.add(m);
            }
            String ka = BibliotecaService.normalizar(c.getArtista());
            porArtista.merge(ka, veces, Long::sum);
            nombreArtista.putIfAbsent(ka, c.getArtista());
            if (!"Desconocido".equalsIgnoreCase(c.getGenero())) porGenero.merge(c.getGenero(), veces, Long::sum);
        }
        s.put("topCanciones", topCanciones);

        s.put("topArtistas", porArtista.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(10)
                .map(e -> {
                    String nombre = nombreArtista.get(e.getKey());
                    var info = biblioteca.artistas().stream()
                            .filter(a -> BibliotecaService.normalizar(a.nombre()).equals(e.getKey())).findFirst();
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("artista", nombre);
                    m.put("veces", e.getValue());
                    m.put("portadaUrl", info.map(a -> a.portadaUrl()).orElse(null));
                    return m;
                }).toList());

        long totalGeneros = porGenero.values().stream().mapToLong(Long::longValue).sum();
        s.put("topGeneros", porGenero.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(8)
                .map(e -> Map.<String, Object>of("genero", e.getKey(), "veces", e.getValue(),
                        "porcentaje", totalGeneros == 0 ? 0 : Math.round(e.getValue() * 1000.0 / totalGeneros) / 10.0))
                .toList());

        // Por mes (últimos 12)
        List<Map<String, Object>> porMes = new ArrayList<>();
        for (Object[] r : reproRepo.findPorMes(userId)) {
            if (porMes.size() >= 12) break;
            int anio = ((Number) r[0]).intValue(), mes = ((Number) r[1]).intValue();
            porMes.add(0, Map.of("anio", anio, "mes", mes, "cantidad", ((Number) r[2]).intValue(),
                    "etiqueta", Month.of(mes).getDisplayName(TextStyle.SHORT, new Locale("es")) + " " + (anio % 100)));
        }
        s.put("porMes", porMes);

        // Por hora del día y racha de días seguidos
        List<LocalDateTime> fechas = reproRepo.findFechas(userId, desde);
        int[] porHora = new int[24];
        Set<LocalDate> dias_ = new HashSet<>();
        for (LocalDateTime f : fechas) {
            porHora[f.getHour()]++;
            dias_.add(f.toLocalDate());
        }
        s.put("porHora", Arrays.stream(porHora).boxed().toList());
        int racha = 0;
        LocalDate d = LocalDate.now();
        if (!dias_.contains(d)) d = d.minusDays(1);
        while (dias_.contains(d)) { racha++; d = d.minusDays(1); }
        s.put("rachaDias", racha);
        s.put("diasActivos", dias_.size());
        return s;
    }

    public static String formatear(long segundos) {
        long h = segundos / 3600, m = (segundos % 3600) / 60;
        if (h > 0) return h + " h " + m + " min";
        return m + " min";
    }

    /** Géneros y artistas favoritos del usuario (para recomendaciones). */
    public Map<String, Long> pesosArtistas(Long userId) {
        Map<String, Long> pesos = new HashMap<>();
        for (Object[] r : reproRepo.findConteoPorCancion(userId, LocalDateTime.now().minusDays(90))) {
            biblioteca.porId(((Number) r[0]).longValue()).ifPresent(c ->
                    pesos.merge(BibliotecaService.normalizar(c.getArtista()), ((Number) r[1]).longValue(), Long::sum));
        }
        for (Long id : favRepo.findCancionIdsByUsuario(userId)) {
            biblioteca.porId(id).ifPresent(c -> pesos.merge(BibliotecaService.normalizar(c.getArtista()), 3L, Long::sum));
        }
        return pesos;
    }

    public Set<String> generosPreferidos(Long userId) {
        Map<String, Long> g = new HashMap<>();
        for (Object[] r : reproRepo.findConteoPorCancion(userId, LocalDateTime.now().minusDays(90))) {
            biblioteca.porId(((Number) r[0]).longValue()).ifPresent(c ->
                    g.merge(BibliotecaService.normalizar(c.getGenero()), ((Number) r[1]).longValue(), Long::sum));
        }
        return g.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(5).map(Map.Entry::getKey).collect(Collectors.toSet());
    }
}
