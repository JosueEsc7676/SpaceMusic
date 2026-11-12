package com.bpm.music.experiencias;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Una canción que alguien aportó a la sesión (APORTE) o su reacción (REPRESENTA, SORPRENDIO, NO_ES_PARA_MI). */
@Entity
@Table(name = "aporte_coincidencia", indexes = @Index(columnList = "sesionId"))
@Getter
@Setter
@NoArgsConstructor
public class AporteCoincidencia {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long sesionId;
    @Column(nullable = false) private Long usuarioId;
    @Column(nullable = false) private Long cancionId;
    @Column(nullable = false, length = 15) private String tipo;
    public AporteCoincidencia(Long sesionId, Long usuarioId, Long cancionId, String tipo) {
        this.sesionId = sesionId; this.usuarioId = usuarioId; this.cancionId = cancionId; this.tipo = tipo;
    }
}
