package com.bpm.music.models;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Dirección UnifiedPush (por ejemplo, de la app ntfy) de un teléfono Android: el servidor le manda un aviso vacío
 * cuando llega un mensaje o una notificación y la app despierta, consulta el resumen y muestra el aviso.
 * Nunca se envía el contenido del mensaje por ahí.
 */
@Entity
@Table(name = "push_destino", indexes = @Index(name = "idx_push_usuario", columnList = "usuarioId"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PushDestino {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long usuarioId;

    @Column(nullable = false, length = 600, unique = true)
    private String endpoint;

    @Column(length = 80)
    private String dispositivo;

    @Builder.Default
    private LocalDateTime creado = LocalDateTime.now();
}
