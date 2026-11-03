package com.bpm.music.models;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Lo que cada usuario guarda en su biblioteca: álbumes y playlists públicas de otros.
 * tipo = ALBUM (clave = nombre del álbum) o PLAYLIST (clave = id de la playlist).
 */
@Entity
@Table(name = "usuario_guardado",
        uniqueConstraints = @UniqueConstraint(columnNames = {"usuario_id", "tipo", "clave"}),
        indexes = @Index(name = "idx_ug_usuario", columnList = "usuario_id, tipo"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UsuarioGuardado {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "usuario_id", nullable = false)
    private Long usuarioId;

    @Column(nullable = false, length = 10)
    private String tipo;

    @Column(nullable = false, length = 255)
    private String clave;

    @Builder.Default
    private LocalDateTime fecha = LocalDateTime.now();
}
