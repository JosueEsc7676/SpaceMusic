package com.bpm.music.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Persona invitada a una playlist colaborativa: puede agregar, quitar y ordenar canciones. */
@Entity
@Table(name = "playlist_colaborador", uniqueConstraints = @UniqueConstraint(columnNames = {"playlistId", "usuarioId"}),
        indexes = @Index(columnList = "usuarioId"))
@Getter
@Setter
@NoArgsConstructor
public class PlaylistColaborador {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long playlistId;

    @Column(nullable = false)
    private Long usuarioId;

    private LocalDateTime fecha = LocalDateTime.now();

    public PlaylistColaborador(Long playlistId, Long usuarioId) {
        this.playlistId = playlistId;
        this.usuarioId = usuarioId;
    }
}
