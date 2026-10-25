package com.bpm.music.models;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Cada reproducción de un usuario. Permite estadísticas personales,
 * "escuchado recientemente" y recomendaciones.
 */
@Entity
@Table(name = "usuario_reproduccion",
        indexes = {
                @Index(name = "idx_ur_usuario", columnList = "usuario_id"),
                @Index(name = "idx_ur_cancion", columnList = "cancion_id"),
                @Index(name = "idx_ur_fecha", columnList = "fecha")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UsuarioReproduccion {

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
    private LocalDateTime fecha = LocalDateTime.now();

    /** Desde dónde se reprodujo: album, playlist, search, radio, etc. */
    private String contexto;

    /** Segundos realmente escuchados (null en registros antiguos). */
    private Integer segundos;
}
