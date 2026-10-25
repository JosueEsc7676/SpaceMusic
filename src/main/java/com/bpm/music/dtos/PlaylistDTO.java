package com.bpm.music.dtos;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlaylistDTO {
    private Long id;
    private String nombre;
    private String descripcion;
    /** Portada personalizada; null si se debe mostrar el mosaico. */
    private String portadaUrl;
    /** Hasta 4 portadas de canciones para el mosaico. */
    private List<String> portadas;
    private int cancionesCount;
    private int duracionTotal;
    private boolean publica;
    /** true si la playlist es del usuario que la pide. */
    private boolean propia;
    private String autor;
    private LocalDateTime fechaCreacion;
    private LocalDateTime fechaActualizacion;
    /** Solo se incluye en el detalle. */
    private List<CancionDTO> canciones;
}
