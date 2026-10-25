package com.bpm.music.services;

import com.bpm.music.repositories.CancionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class EstadisticasService {

    @Autowired
    private CancionRepository repository;

    // =========================
    // RESUMEN GENERAL
    // =========================
    public Map<String, Object> getResumenGeneral() {

        List<Object[]> datos = repository.findEstadisticasGenerales();

        Map<String, Object> resumen = new LinkedHashMap<>();

        if (!datos.isEmpty()) {
            Object[] row = datos.get(0);
            Number totalCanciones    = (Number) row[0];
            Number totalArtistas     = (Number) row[1];
            Number totalAlbumes      = (Number) row[2];
            Number totalGeneros      = (Number) row[3];
            Number totalReproducciones = (Number) row[4];
            Number duracionTotal     = (Number) row[5]; // en segundos
            Number favoritos         = (Number) row[6];

            long segundos = duracionTotal != null ? duracionTotal.longValue() : 0;
            long horas    = segundos / 3600;
            long minutos  = (segundos % 3600) / 60;

            resumen.put("totalCanciones",     totalCanciones != null ? totalCanciones.intValue() : 0);
            resumen.put("totalArtistas",      totalArtistas != null ? totalArtistas.intValue() : 0);
            resumen.put("totalAlbumes",       totalAlbumes != null ? totalAlbumes.intValue() : 0);
            resumen.put("totalGeneros",       totalGeneros != null ? totalGeneros.intValue() : 0);
            resumen.put("totalReproducciones",totalReproducciones != null ? totalReproducciones.intValue() : 0);
            resumen.put("duracionTotalSegundos", segundos);
            resumen.put("duracionFormateada", horas + "h " + minutos + "m");
            resumen.put("totalFavoritos",     favoritos != null ? favoritos.intValue() : 0);
        }

        return resumen;
    }

    // =========================
    // TOP ARTISTAS
    // =========================
    public List<Map<String, Object>> getTopArtistas(int limite) {

        List<Object[]> datos = repository.findTopArtistasPorReproducciones(limite);

        return datos.stream().map(row -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("artista",        (String) row[0]);
            map.put("reproducciones", ((Number) row[1]).intValue());
            map.put("canciones",      ((Number) row[2]).intValue());
            return map;
        }).collect(Collectors.toList());
    }

    // =========================
    // TOP GÉNEROS
    // =========================
    public List<Map<String, Object>> getTopGeneros() {

        List<Object[]> datos = repository.findGenerosPorReproducciones();

        int totalReproducciones = datos.stream()
                .mapToInt(row -> ((Number) row[1]).intValue())
                .sum();

        return datos.stream().map(row -> {
            Map<String, Object> map = new LinkedHashMap<>();
            String genero  = row[0] != null ? (String) row[0] : "Desconocido";
            int repros     = ((Number) row[1]).intValue();
            int canciones  = ((Number) row[2]).intValue();
            double pct     = totalReproducciones > 0
                    ? Math.round((repros * 100.0 / totalReproducciones) * 10.0) / 10.0
                    : 0.0;

            map.put("genero",         genero);
            map.put("reproducciones", repros);
            map.put("canciones",      canciones);
            map.put("porcentaje",     pct);
            return map;
        }).collect(Collectors.toList());
    }

    // =========================
    // CANCIONES POR MES
    // =========================
    public List<Map<String, Object>> getCancionesPorMes() {

        List<Object[]> datos = repository.findCancionesPorMes();

        return datos.stream().map(row -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("anio",     ((Number) row[0]).intValue());
            map.put("mes",      ((Number) row[1]).intValue());
            map.put("cantidad", ((Number) row[2]).intValue());
            // Nombre del mes en español
            map.put("etiqueta", nombreMes(((Number) row[1]).intValue()) + " " + ((Number) row[0]).intValue());
            return map;
        }).collect(Collectors.toList());
    }

    // =========================
    // DISTRIBUCIÓN DE DURACIÓN
    // =========================
    public Map<String, Object> getDistribucionDuracion() {

        List<Object[]> datos = repository.findDistribucionDuracion();

        // Buckets: <2min, 2-4min, 4-6min, >6min
        int menosDe2 = 0, entre2y4 = 0, entre4y6 = 0, masDe6 = 0;

        for (Object[] row : datos) {
            int seg = ((Number) row[0]).intValue();
            if      (seg < 120)  menosDe2++;
            else if (seg < 240)  entre2y4++;
            else if (seg < 360)  entre4y6++;
            else                 masDe6++;
        }

        Map<String, Object> dist = new LinkedHashMap<>();
        dist.put("menosDe2min",  menosDe2);
        dist.put("entre2y4min",  entre2y4);
        dist.put("entre4y6min",  entre4y6);
        dist.put("masDe6min",    masDe6);

        // Duración promedio
        OptionalDouble promedio = datos.stream()
                .mapToInt(r -> ((Number) r[0]).intValue())
                .average();
        dist.put("promedioSegundos", promedio.isPresent() ? (int) promedio.getAsDouble() : 0);

        return dist;
    }

    // =========================
    // HELPERS
    // =========================
    private String nombreMes(int mes) {
        String[] meses = {"Ene","Feb","Mar","Abr","May","Jun",
                          "Jul","Ago","Sep","Oct","Nov","Dic"};
        return (mes >= 1 && mes <= 12) ? meses[mes - 1] : "?";
    }
}
