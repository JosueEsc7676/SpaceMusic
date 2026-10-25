package com.bpm.music.services;

import com.bpm.music.dtos.CancionDTO;
import com.bpm.music.models.Cancion;
import com.bpm.music.repositories.CancionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class SmartShuffleService {

    @Autowired
    private CancionRepository repository;

    @Autowired
    private CancionService cancionService;

    // =========================
    // COLA INTELIGENTE PRINCIPAL
    // Reglas:
    //   1. No repetir artista en canciones consecutivas
    //   2. Ponderar por reproducciones (más escuchadas = más probabilidad)
    //   3. Filtrar por género/artista si se pasa
    // =========================
    public List<CancionDTO> generarColaInteligente(int cantidad, String genero, String artista) {

        List<Cancion> pool = obtenerPool(genero, artista);
        if (pool.isEmpty()) return Collections.emptyList();

        List<Cancion> ponderadas = construirListaPonderada(pool);
        Collections.shuffle(ponderadas);

        List<CancionDTO> cola = new ArrayList<>();
        String ultimoArtista = "";

        // Máximo 3 intentos por slot para no artistas consecutivos
        Deque<Cancion> candidatos = new ArrayDeque<>(ponderadas);
        List<Cancion> diferidos   = new ArrayList<>();

        while (cola.size() < cantidad && (!candidatos.isEmpty() || !diferidos.isEmpty())) {

            // Reincorporar diferidos cuando se agotan candidatos
            if (candidatos.isEmpty()) {
                candidatos.addAll(diferidos);
                diferidos.clear();
                Collections.shuffle((List<?>) candidatos);
            }

            Cancion siguiente = candidatos.poll();
            if (siguiente == null) break;

            if (siguiente.getArtista().equalsIgnoreCase(ultimoArtista) && cola.size() < cantidad - 1) {
                // Diferir para no colocar mismo artista seguido
                diferidos.add(siguiente);
                continue;
            }

            cola.add(cancionService.getById(siguiente.getId()));
            ultimoArtista = siguiente.getArtista();
        }

        return cola;
    }

    // =========================
    // MIX POR GÉNERO
    // =========================
    public List<CancionDTO> mixPorGenero(String genero, int cantidad) {
        return generarColaInteligente(cantidad, genero, null);
    }

    // =========================
    // MIX POR ARTISTA
    // Canciones del artista + canciones del mismo género
    // =========================
    public List<CancionDTO> mixPorArtista(String artista, int cantidad) {

        List<Cancion> delArtista = repository.findByArtistaContainingIgnoreCase(artista);
        if (delArtista.isEmpty()) return Collections.emptyList();

        // Obtener género más común del artista
        String generoArtista = delArtista.stream()
                .filter(c -> c.getGenero() != null && !c.getGenero().isEmpty())
                .collect(Collectors.groupingBy(Cancion::getGenero, Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);

        // Canciones del mismo género (excluyendo al artista)
        List<Cancion> similares = generoArtista != null
                ? repository.findByGeneroIgnoreCase(generoArtista).stream()
                .filter(c -> !c.getArtista().equalsIgnoreCase(artista))
                .collect(Collectors.toList())
                : new ArrayList<>();

        // Mezcla: 60% del artista, 40% similares
        int cuotaArtista  = (int) Math.ceil(cantidad * 0.6);
        int cuotaSimilares = cantidad - cuotaArtista;

        List<Cancion> ponderadasArtista   = construirListaPonderada(delArtista);
        List<Cancion> ponderadasSimilares = construirListaPonderada(similares);

        Collections.shuffle(ponderadasArtista);
        Collections.shuffle(ponderadasSimilares);

        List<Cancion> pool = new ArrayList<>();
        pool.addAll(ponderadasArtista.stream().limit(cuotaArtista).collect(Collectors.toList()));
        pool.addAll(ponderadasSimilares.stream().limit(cuotaSimilares).collect(Collectors.toList()));
        Collections.shuffle(pool);

        // Eliminar duplicados por id
        Set<Long> vistos = new HashSet<>();
        return pool.stream()
                .filter(c -> vistos.add(c.getId()))
                .limit(cantidad)
                .map(c -> cancionService.getById(c.getId()))
                .collect(Collectors.toList());
    }

    // =========================
    // MIX DIARIO
    // Seed basado en la fecha → siempre igual el mismo día, diferente al siguiente
    // =========================
    public List<CancionDTO> mixDiario(int cantidad) {

        LocalDate hoy  = LocalDate.now();
        long seed = hoy.getYear() * 10000L + hoy.getMonthValue() * 100L + hoy.getDayOfMonth();

        List<Cancion> todas = repository.findAll(PageRequest.of(0, 500)).getContent();
        if (todas.isEmpty()) return Collections.emptyList();

        // Shuffle determinista con seed del día
        List<Cancion> mezcladas = new ArrayList<>(todas);
        Collections.shuffle(mezcladas, new Random(seed));

        String ultimoArtista = "";
        List<CancionDTO> cola = new ArrayList<>();
        List<Cancion>    diferidos = new ArrayList<>();

        for (Cancion c : mezcladas) {
            if (cola.size() >= cantidad) break;

            if (c.getArtista().equalsIgnoreCase(ultimoArtista)) {
                diferidos.add(c);
                continue;
            }
            cola.add(cancionService.getById(c.getId()));
            ultimoArtista = c.getArtista();
        }

        // Completar con diferidos si hace falta
        for (Cancion c : diferidos) {
            if (cola.size() >= cantidad) break;
            cola.add(cancionService.getById(c.getId()));
        }

        return cola;
    }


    // =========================
    // RADIO SIMILAR — dado un ID de canción, genera canciones similares
    // Usa: artista de la canción + género de la canción
    // Excluye la canción actual, evita repetir artista consecutivo
    // =========================
    public List<CancionDTO> generarRadioSimilar(Long cancionId, int cantidad) {
        Cancion cancion = repository.findById(cancionId).orElse(null);
        if (cancion == null) return Collections.emptyList();

        String artista = cancion.getArtista();
        String genero  = cancion.getGenero();

        // Canciones del mismo artista (excluyendo la canción actual)
        List<Cancion> delArtista = repository.findByArtistaContainingIgnoreCase(artista)
                .stream()
                .filter(c -> !c.getId().equals(cancionId))
                .collect(Collectors.toList());

        // Canciones del mismo género de OTROS artistas
        List<Cancion> delGenero = (genero != null && !genero.isBlank())
                ? repository.findByGeneroIgnoreCase(genero).stream()
                .filter(c -> !c.getArtista().equalsIgnoreCase(artista))
                .filter(c -> !c.getId().equals(cancionId))
                .collect(Collectors.toList())
                : new ArrayList<>();

        // Si no hay género, usar mix inteligente general
        if (delGenero.isEmpty()) {
            return mixPorArtista(artista, cantidad);
        }

        // Mezcla: 60% del artista, 40% del género
        int cuotaArtista  = (int) Math.ceil(cantidad * 0.6);
        int cuotaGenero   = cantidad - cuotaArtista;

        List<Cancion> poolArtista = construirListaPonderada(delArtista);
        List<Cancion> poolGenero  = construirListaPonderada(delGenero);

        Collections.shuffle(poolArtista);
        Collections.shuffle(poolGenero);

        List<Cancion> pool = new ArrayList<>();
        pool.addAll(poolArtista.stream().limit(cuotaArtista).collect(Collectors.toList()));
        pool.addAll(poolGenero.stream().limit(cuotaGenero).collect(Collectors.toList()));

        // Shuffle final para mezclar artista + género
        Collections.shuffle(pool);

        // Evitar artistas consecutivos
        Set<Long> vistos = new HashSet<>();
        List<CancionDTO> resultado = new ArrayList<>();
        String ultimoArtista = artista; // empezar "como si" el artista acabara de sonar

        List<Cancion> diferidos = new ArrayList<>();
        for (Cancion c : pool) {
            if (vistos.contains(c.getId())) continue;
            if (c.getArtista().equalsIgnoreCase(ultimoArtista) && resultado.size() < cantidad - 1) {
                diferidos.add(c);
                continue;
            }
            vistos.add(c.getId());
            resultado.add(cancionService.getById(c.getId()));
            ultimoArtista = c.getArtista();
            if (resultado.size() >= cantidad) break;
        }

        // Completar con diferidos si hace falta
        for (Cancion c : diferidos) {
            if (resultado.size() >= cantidad) break;
            if (!vistos.contains(c.getId())) {
                vistos.add(c.getId());
                resultado.add(cancionService.getById(c.getId()));
            }
        }

        return resultado;
    }

    // =========================
    // HELPERS PRIVADOS
    // =========================

    // Obtener pool de canciones según filtros opcionales
    private List<Cancion> obtenerPool(String genero, String artista) {
        if (artista != null && !artista.isBlank()) {
            return repository.findByArtistaContainingIgnoreCase(artista);
        }
        if (genero != null && !genero.isBlank()) {
            return repository.findByGeneroIgnoreCase(genero);
        }
        return repository.findAll(PageRequest.of(0, 500)).getContent();
    }

    // Construir lista ponderada: canciones muy escuchadas aparecen más veces
    // Segmenta en 3 niveles de peso para no sobreponderar
    private List<Cancion> construirListaPonderada(List<Cancion> canciones) {

        if (canciones.isEmpty()) return Collections.emptyList();

        int maxRepros = canciones.stream()
                .mapToInt(Cancion::getReproducciones)
                .max()
                .orElse(1);

        List<Cancion> ponderadas = new ArrayList<>();

        for (Cancion c : canciones) {
            int repros = c.getReproducciones();
            double ratio = maxRepros > 0 ? (double) repros / maxRepros : 0;

            // Peso: 1 copia base + hasta 2 extras según popularidad
            int peso = 1;
            if (ratio >= 0.66) peso = 3;
            else if (ratio >= 0.33) peso = 2;

            for (int i = 0; i < peso; i++) {
                ponderadas.add(c);
            }
        }

        return ponderadas;
    }
}
