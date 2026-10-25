package com.bpm.music.models;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Playlist {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String nombre;

    @Column(length = 500)
    private String descripcion;

    /** URL de la portada personalizada (relativa, p.ej. /api/playlists/imagen/x.jpg). */
    @Column(length = 500)
    private String portadaUrl;

    /** Ruta del archivo de la portada personalizada en disco. */
    @Column(length = 500)
    private String portadaPath;

    /** Dueño de la playlist. */
    @Column(name = "usuario_id")
    private Long usuarioId;

    /** Si es true, otros usuarios pueden verla y escucharla. */
    @Column(nullable = false, columnDefinition = "boolean default false")
    @Builder.Default
    private boolean publica = false;

    @Builder.Default
    private int cancionesCount = 0;

    @Builder.Default
    private LocalDateTime fechaCreacion = LocalDateTime.now();

    private LocalDateTime fechaActualizacion;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "playlist_cancion",
            joinColumns = @JoinColumn(name = "playlist_id"),
            inverseJoinColumns = @JoinColumn(name = "cancion_id")
    )
    @Builder.Default
    private List<Cancion> canciones = new ArrayList<>();

    public void updateCancionesCount() {
        this.cancionesCount = this.canciones.size();
        this.fechaActualizacion = LocalDateTime.now();
    }
}
