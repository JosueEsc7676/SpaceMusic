// services/SmartRecommendationService.java
package com.bpm.music.services;

import com.bpm.music.dtos.CancionDTO;
import com.bpm.music.models.Cancion;
import com.bpm.music.models.HistorialReproduccion;
import com.bpm.music.repositories.CancionRepository;
import com.bpm.music.repositories.HistorialReproduccionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Motor de recomendaciones con scoring ponderado.
 *
 * Puntuación por coincidencia:
 *   +10  mismo artista
 *   + 5  mismo género
 *   + 3  mismo álbum
 *   + 1  mismo formato de archivo (bonus menor)
 *
 * Penalizaciones:
 *   -50  ya en historial reciente de la sesión (excluir)
 *
 * El resultado final combina score determinista + ruido aleatorio
 * para que el mismo artista no siempre salga primero.
 */
@Service
public class SmartRecommendationService {

    // ── Pesos del scoring ──────────────────────────────────────────────────
    private static final int PESO_MISMO_ARTISTA = 10;
    private static final int PESO_MISMO_GENERO  =  5;
    private static final int PESO_MISMO_ALBUM   =  3;
    private static final int PESO_POPULARES     =  2;  // top 20 por reproducciones

    // ── Ventana de historial reciente a excluir ────────────────────────────
    private static final int VENTANA_HISTORIAL   = 20;
    private static final int MIN_ARTISTA_GAP     =  3;  // no repetir artista en N slots

    // ── Caché de recomendaciones por sesión ───────────────────────────────
    private final Map<String, List<CancionDTO>> cacheRecomendaciones = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 60_000; // 1 minuto
    private final Map<String, Long> cacheTiempos = new ConcurrentHashMap<>();

    @Autowired private CancionRepository cancionRepository;
    @Autowired private HistorialReproduccionRepository historialRepository;
    @Autowired private CancionService cancionService;

    // ═══════════════════════════════════════════════════════════════════════
    // MÉTODO PRINCIPAL: obtener siguiente canción recomendada
    // ═══════════════════════════════════════════════════════════════════════
    public CancionDTO getSiguienteRecomendada(Long cancionActualId, String sesionId) {

        Cancion actual = cancionRepository.findById(cancionActualId).orElse(null);
        if (actual == null) return getFallbackAleatorio(sesionId);

        // Obtener historial reciente para exclusión
        List<Long> idsRecientes = historialRepository
                .findCancionIdsRecientes(sesionId, VENTANA_HISTORIAL);
        Set<Long> excluidos = new HashSet<>(idsRecientes);
        excluidos.add(cancionActualId); // nunca repetir la que acaba de sonar

        // Construir candidatos con scoring
        List<CancionScorada> candidatos = construirCandidatos(actual, excluidos);

        if (candidatos.isEmpty()) {
            // Sin candidatos: limpiar exclusiones y volver a intentar
            excluidos.clear();
            excluidos.add(cancionActualId);
            candidatos = construirCandidatos(actual, excluidos);
        }

        if (candidatos.isEmpty()) return getFallbackAleatorio(sesionId);

        // Ordenar: score DESC con ruido aleatorio para variedad
        candidatos.sort((a, b) -> {
            double scoreA = a.score + (Math.random() * 3);
            double scoreB = b.score + (Math.random() * 3);
            return Double.compare(scoreB, scoreA);
        });

        // Aplicar regla de no-artista-consecutivo
        String artistaActual = actual.getArtista();
        List<String> artistasRecientes = historialRepository
                .findArtistasRecientes(sesionId, MIN_ARTISTA_GAP);

        for (CancionScorada candidato : candidatos) {
            String artCandidato = candidato.cancion.getArtista();
            boolean artReciente = artistasRecientes.stream()
                    .limit(MIN_ARTISTA_GAP)
                    .anyMatch(a -> a.equalsIgnoreCase(artCandidato));

            if (!artCandidato.equalsIgnoreCase(artistaActual) && !artReciente) {
                return cancionService.convertToDTO(candidato.cancion);
            }
        }

        // Si todos son del mismo artista, devolver el mejor con mayor score igualmente
        return cancionService.convertToDTO(candidatos.get(0).cancion);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SCORING: construir lista ponderada de candidatos
    // ═══════════════════════════════════════════════════════════════════════
    private List<CancionScorada> construirCandidatos(Cancion referencia, Set<Long> excluidos) {

        List<Cancion> todas = cancionRepository.findAll(PageRequest.of(0, 500)).getContent();
        Set<Long> popularIds = cancionRepository.findTop20ByOrderByReproduccionesDesc()
                .stream().map(Cancion::getId).collect(Collectors.toSet());

        List<CancionScorada> resultado = new ArrayList<>();

        for (Cancion c : todas) {
            if (excluidos.contains(c.getId())) continue;

            int score = calcularScore(referencia, c, popularIds);
            if (score > 0) {
                resultado.add(new CancionScorada(c, score));
            }
        }

        return resultado;
    }

    private int calcularScore(Cancion ref, Cancion candidato, Set<Long> popularIds) {
        int score = 0;

        // Artista idéntico
        if (ref.getArtista() != null && ref.getArtista().equalsIgnoreCase(candidato.getArtista())) {
            score += PESO_MISMO_ARTISTA;
        }

        // Mismo género (comparación parcial para cubrir "Rock/Pop" vs "Rock")
        if (ref.getGenero() != null && candidato.getGenero() != null) {
            String g1 = ref.getGenero().toLowerCase();
            String g2 = candidato.getGenero().toLowerCase();
            if (g1.equals(g2) || g1.contains(g2) || g2.contains(g1)) {
                score += PESO_MISMO_GENERO;
            }
        }

        // Mismo álbum
        if (ref.getAlbum() != null && ref.getAlbum().equalsIgnoreCase(candidato.getAlbum())
                && !ref.getAlbum().equalsIgnoreCase("Single")) {
            score += PESO_MISMO_ALBUM;
        }

        // Bonus por popularidad
        if (popularIds.contains(candidato.getId())) {
            score += PESO_POPULARES;
        }

        return score;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // GUARDAR EN HISTORIAL
    // ═══════════════════════════════════════════════════════════════════════
    @Transactional
    public void registrarReproduccion(Long cancionId, String sesionId,
                                       Integer segundosEscuchados, String fuente) {
        Cancion cancion = cancionRepository.findById(cancionId).orElse(null);
        if (cancion == null) return;

        HistorialReproduccion h = HistorialReproduccion.builder()
                .sesionId(sesionId)
                .cancion(cancion)
                .fechaReproduccion(LocalDateTime.now())
                .segundosEscuchados(segundosEscuchados != null ? segundosEscuchados : 0)
                .fuente(fuente != null ? fuente : "MANUAL")
                .build();

        historialRepository.save(h);

        // Invalidar caché de recomendaciones de esta sesión
        cacheRecomendaciones.remove(sesionId);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // FALLBACK: cuando no hay candidatos con score > 0
    // ═══════════════════════════════════════════════════════════════════════
    private CancionDTO getFallbackAleatorio(String sesionId) {
        List<Long> idsRecientes = historialRepository
                .findCancionIdsRecientes(sesionId, VENTANA_HISTORIAL);
        Set<Long> excluidos = new HashSet<>(idsRecientes);

        List<Cancion> todas = cancionRepository.findAll(PageRequest.of(0, 500)).getContent();
        List<Cancion> candidatos = todas.stream()
                .filter(c -> !excluidos.contains(c.getId()))
                .collect(Collectors.toList());

        if (candidatos.isEmpty()) candidatos = todas; // último recurso

        Collections.shuffle(candidatos);
        return cancionService.convertToDTO(candidatos.get(0));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // LIMPIEZA PROGRAMADA: borrar historial > 7 días (cada 24h)
    // ═══════════════════════════════════════════════════════════════════════
    @Scheduled(fixedDelay = 86_400_000)
    @Transactional
    public void limpiarHistorialAntiguo() {
        LocalDateTime umbral = LocalDateTime.now().minusDays(7);
        historialRepository.deleteOlderThan(umbral);
        System.out.println("🧹 Historial antiguo limpiado");
    }

    // ── Record interno para candidatos con score ────────────────────────
    private record CancionScorada(Cancion cancion, int score) {}
}
