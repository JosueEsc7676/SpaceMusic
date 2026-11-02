package com.bpm.music.dtos;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Datos ligeros de una canción para el navegador.
 * No incluye la ruta del archivo en el servidor ni la letra completa.
 */
@Getter
@Setter
@NoArgsConstructor
public class CancionDTO {
    private Long id;
    private String nombre;
    private String artista;
    private String album;
    private String genero;
    private int duracionSegundos;
    private int reproducciones;
    private String portadaUrl;
    private String audioUrl;
    private String formato;
    private Integer anio;
    private Integer numeroPista;
    /** Tiene letra sincronizada (LRC). */
    private boolean letraSincronizada;
    /** Tiene alguna letra (sincronizada o texto plano). */
    private boolean tieneLetra;
    private LocalDateTime fechaAgregada;

    /** Tiene portada embebida en la base de datos (no se envía). */
    @JsonIgnore
    private boolean tienePortada;

    /** Constructor usado por la consulta de proyección del repositorio. */
    public CancionDTO(Long id, String nombre, String artista, String album, String genero,
                      Integer duracionSegundos, Integer reproducciones,
                      Boolean tienePortada, String portadaHash, String portadaExterna,
                      String formato, Integer anio, Integer numeroPista,
                      Boolean tieneLrc, Boolean tieneTexto, LocalDateTime fechaAgregada) {
        this.id = id;
        this.nombre = nombre;
        this.artista = (artista == null || artista.isBlank()) ? "Desconocido" : artista;
        this.album = (album == null || album.isBlank()) ? "Single" : album;
        this.genero = (genero == null || genero.isBlank()) ? "Desconocido" : genero;
        this.duracionSegundos = duracionSegundos != null ? duracionSegundos : 0;
        this.reproducciones = reproducciones != null ? reproducciones : 0;
        this.tienePortada = Boolean.TRUE.equals(tienePortada);
        this.portadaUrl = construirPortadaUrl(id, Boolean.TRUE.equals(tienePortada), portadaHash, portadaExterna);
        this.audioUrl = "/api/canciones/" + id + "/audio";
        this.formato = formato;
        this.anio = anio;
        this.numeroPista = numeroPista;
        this.letraSincronizada = Boolean.TRUE.equals(tieneLrc);
        this.tieneLetra = this.letraSincronizada || Boolean.TRUE.equals(tieneTexto);
        this.fechaAgregada = fechaAgregada;
    }

    public static String construirPortadaUrl(Long id, boolean tienePortada, String portadaHash, String portadaExterna) {
        if (!tienePortada && portadaExterna != null && portadaExterna.startsWith("http")) {
            return portadaExterna;
        }
        // El endpoint devuelve la portada embebida o una imagen generada
        // La huella en la URL hace que el navegador nunca muestre una portada vieja
        return "/api/portada/db/" + id + (tienePortada && portadaHash != null ? "?v=" + portadaHash : "");
    }
}
