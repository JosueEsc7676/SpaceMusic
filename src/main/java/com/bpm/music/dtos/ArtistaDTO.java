package com.bpm.music.dtos;

/** Resumen de un artista calculado a partir de la biblioteca. */
public record ArtistaDTO(
        String nombre,
        int totalCanciones,
        int totalAlbumes,
        int totalReproducciones,
        int duracionTotal,
        String portadaUrl
) {}
