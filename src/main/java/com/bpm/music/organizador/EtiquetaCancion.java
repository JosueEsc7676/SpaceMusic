package com.bpm.music.organizador;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Etiqueta de una canción con su origen bien separado:
 *  - "proveedor": dato verificado de un servicio (MusicBrainz, YouTube Music);
 *  - "audio": calculado del sonido (ffmpeg);
 *  - "ia": inferido por un modelo local. Nunca se aplica solo: queda PROPUESTA hasta revisarlo.
 */
@Entity
@Table(name = "etiqueta_cancion", indexes = {@Index(columnList = "cancionId"), @Index(columnList = "origen,estado")})
@Getter
@Setter
@NoArgsConstructor
public class EtiquetaCancion {

    public enum Estado { PROPUESTA, ACEPTADA, RECHAZADA }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long cancionId;

    /** estilo | contexto */
    @Column(nullable = false, length = 15)
    private String tipo;

    @Column(nullable = false, length = 40)
    private String valor;

    /** proveedor | audio | ia */
    @Column(nullable = false, length = 10)
    private String origen;

    @Column(length = 60)
    private String modelo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Estado estado;

    private LocalDateTime fecha = LocalDateTime.now();
}
