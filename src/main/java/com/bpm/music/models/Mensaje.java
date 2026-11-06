package com.bpm.music.models;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** Mensaje directo entre dos amigos (usuarios que se siguen mutuamente). */
@Entity
@Table(name = "mensaje", indexes = {
        @Index(name = "idx_msg_para", columnList = "para_id, leido"),
        @Index(name = "idx_msg_par", columnList = "de_id, para_id")})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Mensaje {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "de_id", nullable = false)
    private Long deId;

    @Column(name = "para_id", nullable = false)
    private Long paraId;

    @Column(nullable = false, length = 1000)
    private String texto;

    /** Canción compartida en el mensaje (opcional). */
    private Long cancionId;

    @Builder.Default
    private LocalDateTime fecha = LocalDateTime.now();

    @Builder.Default
    private boolean leido = false;
}
