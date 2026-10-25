package com.bpm.music.models;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * Cada vez que un usuario reproduce una canción se registra aquí.
 * Esto permite estadísticas POR USUARIO separadas de las globales.
 */
@Entity
@Table(name = "usuario_reproduccion",
       indexes = {
           @Index(name = "idx_ur_usuario", columnList = "usuario_id"),
           @Index(name = "idx_ur_cancion", columnList = "cancion_id"),
           @Index(name = "idx_ur_fecha",   columnList = "fecha")
       })
@Data
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

    // Para saber desde dónde se reprodujo
    private String contexto; // 'album', 'playlist', 'search', 'radio', etc.
}
