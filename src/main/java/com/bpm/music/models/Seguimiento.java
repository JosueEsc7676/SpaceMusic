package com.bpm.music.models;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** Un usuario sigue a otro. Si se siguen los dos, son amigos. */
@Entity
@Table(name = "seguimiento",
        uniqueConstraints = @UniqueConstraint(columnNames = {"seguidor_id", "seguido_id"}),
        indexes = {@Index(name = "idx_seg_seguidor", columnList = "seguidor_id"),
                @Index(name = "idx_seg_seguido", columnList = "seguido_id")})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Seguimiento {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "seguidor_id", nullable = false)
    private Long seguidorId;

    @Column(name = "seguido_id", nullable = false)
    private Long seguidoId;

    @Builder.Default
    private LocalDateTime fecha = LocalDateTime.now();
}
