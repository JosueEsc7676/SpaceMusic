package com.bpm.music.services;

import com.bpm.music.dtos.ArtistaDTO;
import com.bpm.music.dtos.CancionDTO;
import com.bpm.music.repositories.CancionRepository;
import com.bpm.music.repositories.UsuarioRepository;
import com.bpm.music.repositories.UsuarioReproduccionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/** Estadísticas globales de la biblioteca (panel de administración). */
@Service
public class EstadisticasService {

    private static final String[] MESES = {"Ene", "Feb", "Mar", "Abr", "May", "Jun", "Jul", "Ago", "Sep", "Oct", "Nov", "Dic"};

    private final BibliotecaService biblioteca;
    private final CancionRepository cancionRepo;
    private final UsuarioRepository usuarioRepo;
    private final UsuarioReproduccionRepository reproRepo;

    public EstadisticasService(BibliotecaService biblioteca, CancionRepository cancionRepo,
                               UsuarioRepository usuarioRepo, UsuarioReproduccionRepository reproRepo) {
        this.biblioteca = biblioteca;
        this.cancionRepo = cancionRepo;
        this.usuarioRepo = usuarioRepo;
        this.reproRepo = reproRepo;
    }

    public Map<String, Object> resumen() {
        List<CancionDTO> todas = biblioteca.todas();
        long segundos = todas.stream().mapToLong(CancionDTO::getDuracionSegundos).sum();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("totalCanciones", todas.size());
        r.put("totalArtistas", biblioteca.artistas().size());
        r.put("totalAlbumes", biblioteca.albumes().size());
        r.put("totalGeneros", biblioteca.generos().size());
        r.put("totalReproducciones", todas.stream().mapToLong(CancionDTO::getReproducciones).sum());
        r.put("duracionTotalSegundos", segundos);
        r.put("duracionFormateada", UsuarioService.formatear(segundos));
        r.put("conLetra", todas.stream().filter(CancionDTO::isTieneLetra).count());
        r.put("conLetraSincronizada", todas.stream().filter(CancionDTO::isLetraSincronizada).count());
        r.put("totalUsuarios", usuarioRepo.count());
        LocalDateTime hace30 = LocalDateTime.now().minusDays(30);
        r.put("usuariosActivos30d", reproRepo.countUsuariosActivosDesde(hace30));
        r.put("reproducciones30d", reproRepo.countByFechaAfter(hace30));
        r.put("formatos", todas.stream()
                .collect(Collectors.groupingBy(c -> c.getFormato() == null ? "?" : c.getFormato().toUpperCase(), TreeMap::new, Collectors.counting())));
        return r;
    }

    public List<Map<String, Object>> topArtistas(int limite) {
        return biblioteca.artistas().stream()
                .sorted(Comparator.comparingInt(ArtistaDTO::totalReproducciones).reversed())
                .limit(limite)
                .map(a -> Map.<String, Object>of("artista", a.nombre(), "reproducciones", a.totalReproducciones(),
                        "canciones", a.totalCanciones(), "portadaUrl", Objects.toString(a.portadaUrl(), "")))
                .toList();
    }

    public List<Map<String, Object>> topGeneros() {
        List<Map<String, Object>> g = biblioteca.generos();
        long total = g.stream().mapToLong(m -> ((Number) m.get("totalCanciones")).longValue()).sum();
        return g.stream().map(m -> {
            Map<String, Object> x = new LinkedHashMap<>(m);
            long n = ((Number) m.get("totalCanciones")).longValue();
            x.put("genero", m.get("nombre"));
            x.put("canciones", n);
            x.put("porcentaje", total == 0 ? 0 : Math.round(n * 1000.0 / total) / 10.0);
            return x;
        }).toList();
    }

    public List<Map<String, Object>> cancionesPorMes() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] row : cancionRepo.findCancionesPorMes()) {
            int anio = ((Number) row[0]).intValue(), mes = ((Number) row[1]).intValue();
            out.add(0, Map.of("anio", anio, "mes", mes, "cantidad", ((Number) row[2]).intValue(),
                    "etiqueta", MESES[Math.max(0, Math.min(11, mes - 1))] + " " + (anio % 100)));
        }
        return out;
    }

    /** Lo más escuchado por todos en los últimos días. */
    public List<CancionDTO> tendencias(int dias, int limite) {
        List<Long> ids = reproRepo.findTendencias(LocalDateTime.now().minusDays(dias), PageRequest.of(0, limite))
                .stream().map(r -> ((Number) r[0]).longValue()).toList();
        return biblioteca.porIds(ids);
    }
}
