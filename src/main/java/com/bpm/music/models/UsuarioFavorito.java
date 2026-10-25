package com.bpm.music.models;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * Favoritos por usuario — independientes de los favoritos globales de Cancion.
 */
@Entity
@Table(name = "usuario_favorito",
       uniqueConstraints = @UniqueConstraint(columnNames = {"usuario_id", "cancion_id"}),
       indexes = @Index(name = "idx_uf_usuario", columnList = "usuario_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UsuarioFavorito {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario usuario;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cancion_id", nullable = false)
    private Cancion cancion;

    @Builder.Default
    private LocalDateTime fechaAgregado = LocalDateTime.now();
}
