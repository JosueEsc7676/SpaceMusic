package com.bpm.music.experiencias;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** "No recibir más dedicatorias de esta persona". */
@Entity
@Table(name = "bloqueo_dedicatoria", uniqueConstraints = @UniqueConstraint(columnNames = {"usuarioId", "bloqueadoId"}))
@Getter
@Setter
@NoArgsConstructor
public class BloqueoDedicatoria {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long usuarioId;
    @Column(nullable = false) private Long bloqueadoId;
    private LocalDateTime fecha = LocalDateTime.now();
    public BloqueoDedicatoria(Long usuarioId, Long bloqueadoId) { this.usuarioId = usuarioId; this.bloqueadoId = bloqueadoId; }
}
