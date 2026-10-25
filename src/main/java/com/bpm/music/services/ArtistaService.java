package com.bpm.music.services;

import com.bpm.music.dtos.ArtistaDTO;
import com.bpm.music.dtos.CancionDTO;
import com.bpm.music.models.Cancion;
import com.bpm.music.repositories.CancionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class ArtistaService {

    @Autowired
    private CancionRepository repository;

    @Autowired
    private CancionService cancionService;

    @Value("${music.base-url:http://localhost:8080}")
    private String baseUrl;

    // =========================
    // LISTAR TODOS LOS ARTISTAS
    // =========================
    public List<ArtistaDTO> listarArtistas() {

        // Traer todas las canciones (sin portada para no reventar memoria)
        List<Object[]> datos = repository.findArtistasConInfo();

        return datos.stream().map(row -> {
            String nombre        = (String)  row[0];
            Number canciones     = (Number)  row[1];
            Number albums        = (Number)  row[2];
            Number reproducciones= (Number)  row[3];
            Number primeraId     = (Number)  row[4];

            String portada = baseUrl + "/api/portada/db/" + primeraId.longValue();

            return new ArtistaDTO(
                    nombre,
                    canciones.intValue(),
                    albums.intValue(),
                    reproducciones.intValue(),
                    portada
            );
        }).collect(Collectors.toList());
    }

    // =========================
    // DETALLE DE UN ARTISTA
    // Albums agrupados con sus canciones
    // =========================
    public Map<String, Object> getDetalleArtista(String nombre) {

        List<Cancion> canciones = repository.findByArtistaContainingIgnoreCase(nombre);
        if (canciones.isEmpty()) return null;

        // Agrupar por álbum
        Map<String, List<CancionDTO>> porAlbum = canciones.stream()
                .map(c -> cancionService.getById(c.getId()))
                .collect(Collectors.groupingBy(
                        dto -> (dto.getAlbum() == null || dto.getAlbum().isEmpty()) ? "Single" : dto.getAlbum()
                ));

        // Construir lista de álbumes con portada y canciones
        List<Map<String, Object>> albumes = porAlbum.entrySet().stream()
                .map(entry -> {
                    Map<String, Object> albumMap = new LinkedHashMap<>();
                    albumMap.put("nombre", entry.getKey());
                    albumMap.put("canciones", entry.getValue());
                    albumMap.put("portadaUrl", entry.getValue().get(0).getPortadaUrl());
                    albumMap.put("totalCanciones", entry.getValue().size());
                    albumMap.put("duracionTotal", entry.getValue().stream()
                            .mapToInt(CancionDTO::getDuracionSegundos).sum());
                    return albumMap;
                })
                .sorted(Comparator.comparing(m -> (String) m.get("nombre")))
                .collect(Collectors.toList());

        // Top 5 canciones del artista
        List<CancionDTO> topCanciones = canciones.stream()
                .map(c -> cancionService.getById(c.getId()))
                .sorted(Comparator.comparingInt(CancionDTO::getReproducciones).reversed())
                .limit(5)
                .collect(Collectors.toList());

        int totalReproducciones = canciones.stream().mapToInt(Cancion::getReproducciones).sum();
        int duracionTotal = canciones.stream().mapToInt(Cancion::getDuracionSegundos).sum();

        Map<String, Object> resultado = new LinkedHashMap<>();
        resultado.put("nombre", nombre);
        resultado.put("totalCanciones", canciones.size());
        resultado.put("totalAlbumes", porAlbum.size());
        resultado.put("totalReproducciones", totalReproducciones);
        resultado.put("duracionTotal", duracionTotal);
        resultado.put("portadaUrl", baseUrl + "/api/portada/db/" + canciones.get(0).getId());
        resultado.put("albumes", albumes);
        resultado.put("topCanciones", topCanciones);

        return resultado;
    }

    // =========================
    // TOP CANCIONES DE UN ARTISTA
    // =========================
    public Map<String, Object> getTopCancionesArtista(String nombre) {

        List<Cancion> canciones = repository.findByArtistaContainingIgnoreCase(nombre);

        List<CancionDTO> top = canciones.stream()
                .map(c -> cancionService.getById(c.getId()))
                .sorted(Comparator.comparingInt(CancionDTO::getReproducciones).reversed())
                .limit(10)
                .collect(Collectors.toList());

        Map<String, Object> resultado = new LinkedHashMap<>();
        resultado.put("artista", nombre);
        resultado.put("canciones", top);
        return resultado;
    }
}
