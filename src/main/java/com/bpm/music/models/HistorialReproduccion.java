// models/HistorialReproduccion.java
package com.bpm.music.models;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * Historial de reproducciones por sesión/usuario.
 * Se usa para:
 *   1. Excluir canciones recientes de las recomendaciones
 *   2. Calcular estadísticas de escucha
 *   3. Personalizar el scoring a futuro
 */
@Entity
@Table(name = "historial_reproduccion",
       indexes = {
           @Index(name = "idx_sesion_fecha",  columnList = "sesion_id, fecha_reproduccion DESC"),
           @Index(name = "idx_cancion_sesion", columnList = "cancion_id, sesion_id")
       })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HistorialReproduccion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** ID de sesión anónima del navegador (localStorage key) */
    @Column(name = "sesion_id", nullable = false, length = 64)
    private String sesionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cancion_id", nullable = false)
    private Cancion cancion;

    @Column(name = "fecha_reproduccion", nullable = false)
    private LocalDateTime fechaReproduccion;

    /** Segundos escuchados antes de saltar (0 = escuchó completa) */
    @Column(name = "segundos_escuchados")
    private Integer segundosEscuchados;

    /** Fuente de reproducción: MANUAL, RADIO, QUEUE, PLAYLIST, SHUFFLE */
    @Column(name = "fuente", length = 20)
    private String fuente;

    @PrePersist
    protected void onPersist() {
        if (fechaReproduccion == null) {
            fechaReproduccion = LocalDateTime.now();
        }
    }
}
