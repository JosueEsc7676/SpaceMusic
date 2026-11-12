package com.bpm.music.organizador;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Características CALCULADAS del audio con ffmpeg (no las inventa un modelo de texto):
 * sonoridad integrada (LUFS) y rango dinámico (LRA), y una "energía" estimada a partir de ambas.
 * Es una aproximación: no mide tempo ni ánimo.
 */
@Entity
@Table(name = "caracteristica_audio")
@Getter
@Setter
@NoArgsConstructor
public class CaracteristicaAudio {

    @Id
    private Long cancionId;

    private Double lufs;
    private Double lra;

    /** 0 (tranquila) a 1 (intensa), estimada por volumen y dinámica. */
    private Double energia;

    /** Huella del archivo analizado (tamaño y fecha): si cambia, se vuelve a analizar. */
    @Column(length = 40)
    private String huellaArchivo;

    @Column(length = 120)
    private String metodo;

    @Column(length = 200)
    private String error;

    private LocalDateTime fecha = LocalDateTime.now();
}
