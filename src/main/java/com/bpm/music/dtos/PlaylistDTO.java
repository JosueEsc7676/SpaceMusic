package com.bpm.music.dtos;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlaylistDTO {
    private Long id;
    private String nombre;
    private String descripcion;
    private String portadaUrl;
    private int cancionesCount;
    private List<CancionDTO> canciones;

    public PlaylistDTO(Long id, String nombre, String descripcion, String portadaUrl, int cancionesCount) {
        this.id = id;
        this.nombre = nombre;
        this.descripcion = descripcion;
        this.portadaUrl = portadaUrl;
        this.cancionesCount = cancionesCount;
    }
}