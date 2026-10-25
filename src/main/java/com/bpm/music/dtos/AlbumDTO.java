package com.bpm.music.dtos;

import java.util.List;

/** Álbum calculado a partir de la biblioteca. */
public record AlbumDTO(
        String nombre,
        String artista,
        Integer anio,
        int totalCanciones,
        int duracionTotal,
        int reproducciones,
        String portadaUrl,
        List<CancionDTO> canciones
) {
    public AlbumDTO sinCanciones() {
        return new AlbumDTO(nombre, artista, anio, totalCanciones, duracionTotal, reproducciones, portadaUrl, null);
    }
}
