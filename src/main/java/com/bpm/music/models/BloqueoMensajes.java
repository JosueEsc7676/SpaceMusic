package com.bpm.music.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Una persona bloqueó a otra en los mensajes: ninguna de las dos puede escribirle a la otra. */
@Entity
@Table(name = "bloqueo_mensajes", uniqueConstraints = @UniqueConstraint(columnNames = {"usuario_id", "bloqueado_id"}))
@Getter
@Setter
@NoArgsConstructor
public class BloqueoMensajes {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "usuario_id", nullable = false) private Long usuarioId;
    @Column(name = "bloqueado_id", nullable = false) private Long bloqueadoId;
    private LocalDateTime fecha = LocalDateTime.now();

    public BloqueoMensajes(Long usuarioId, Long bloqueadoId) { this.usuarioId = usuarioId; this.bloqueadoId = bloqueadoId; }
}
