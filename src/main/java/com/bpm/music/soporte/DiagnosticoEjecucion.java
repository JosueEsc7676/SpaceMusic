package com.bpm.music.soporte;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Una ejecución de diagnóstico (completo, prueba suelta o revisión de la biblioteca) y sus resultados. */
@Entity
@Table(name = "soporte_diagnostico", indexes = @Index(name = "idx_diag_inicio", columnList = "inicio"))
@Getter
@Setter
@NoArgsConstructor
public class DiagnosticoEjecucion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** completo, prueba, biblioteca */
    @Column(nullable = false, length = 20)
    private String tipo;

    private LocalDateTime inicio;
    private LocalDateTime fin;

    @Column(length = 60)
    private String usuario;

    /** EN_CURSO, TERMINADO, FALLIDO */
    @Column(length = 12)
    private String estado;

    private int correctas;
    private int advertencias;
    private int fallidas;
    private int noDisponibles;

    /** Resultados en JSON (lista de pruebas o resumen de la biblioteca). */
    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String resultados;
}
