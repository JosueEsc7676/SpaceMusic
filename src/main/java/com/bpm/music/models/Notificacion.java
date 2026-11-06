package com.bpm.music.models;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** Aviso para un usuario: alguien lo empezó a seguir, ahora son amigos, etc. */
@Entity
@Table(name = "notificacion", indexes = @Index(name = "idx_notif_usuario", columnList = "usuario_id, leida"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notificacion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "usuario_id", nullable = false)
    private Long usuarioId;

    /** SEGUIDOR, AMIGO */
    @Column(nullable = false, length = 20)
    private String tipo;

    /** Quién la provocó (para mostrar su foto). */
    private Long deId;

    @Column(nullable = false, length = 300)
    private String texto;

    /** Pantalla que se abre al tocarla (p. ej. "#/u/maria"). */
    @Column(length = 200)
    private String ruta;

    @Builder.Default
    private LocalDateTime fecha = LocalDateTime.now();

    @Builder.Default
    private boolean leida = false;
}
