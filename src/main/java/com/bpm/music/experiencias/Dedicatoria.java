package com.bpm.music.experiencias;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** "Una canción para este momento": una canción del servidor, un mensaje y el segundo exacto. */
@Entity
@Table(name = "dedicatoria", indexes = {@Index(columnList = "paraId"), @Index(columnList = "deId")})
@Getter
@Setter
@NoArgsConstructor
public class Dedicatoria {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long deId;
    @Column(nullable = false) private Long paraId;
    @Column(nullable = false) private Long cancionId;
    /** Segundo de la canción donde empieza la dedicatoria. */
    private int segundo;
    @Column(length = 280) private String mensaje;
    @Column(length = 280) private String respuesta;
    private boolean vista;
    /** El destinatario la guardó en "Canciones que me dedicaron". */
    private boolean guardada;
    /** El destinatario la quitó de su lista. */
    private boolean oculta;
    private LocalDateTime fecha = LocalDateTime.now();
    private LocalDateTime respondidaEn;
}
