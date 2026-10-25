// models/Playlist.java
package com.bpm.music.models;

import jakarta.persistence.*;
import lombok.*;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Playlist {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String nombre;

    private String descripcion;

    @Column(length = 500)
    private String portadaUrl;

    @Column(length = 500)
    private String portadaPath;

    // ✅ Dueño de la playlist — cada usuario ve solo las suyas
    @Column(name = "usuario_id")
    private Long usuarioId;

    @Builder.Default
    private int cancionesCount = 0;

    @Builder.Default
    private LocalDateTime fechaCreacion = LocalDateTime.now();

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "playlist_cancion",
            joinColumns = @JoinColumn(name = "playlist_id"),
            inverseJoinColumns = @JoinColumn(name = "cancion_id")
    )
    @JsonIgnoreProperties("playlists")
    @Builder.Default
    private List<Cancion> canciones = new ArrayList<>();

    public void updateCancionesCount() {
        this.cancionesCount = this.canciones.size();
    }
}
