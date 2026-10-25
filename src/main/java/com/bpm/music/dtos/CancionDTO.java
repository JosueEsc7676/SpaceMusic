// dtos/CancionDTO.java
package com.bpm.music.dtos;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CancionDTO {
    private Long id;
    private String nombre;
    private String artista;
    private String album;
    private String portadaUrl;
    private boolean favorito;
    private int reproducciones;
    private int duracionSegundos;
    private String audioUrl;
    private String rutaArchivo;
    private String letra;           // ← NUEVO CAMPO
    private String letraSincronizada;
}