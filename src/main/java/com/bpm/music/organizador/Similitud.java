package com.bpm.music.organizador;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** "Si te gusta A, B se le parece" entre dos canciones del servidor, según una fuente externa verificada. */
@Entity
@Table(name = "cancion_similitud", uniqueConstraints = @UniqueConstraint(columnNames = {"cancionId", "similarId", "fuente"}),
        indexes = @Index(columnList = "cancionId"))
@Getter
@Setter
@NoArgsConstructor
public class Similitud {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long cancionId;

    @Column(nullable = false)
    private Long similarId;

    @Column(nullable = false, length = 20)
    private String fuente;

    /** 0..1: más alto = más arriba en la lista del proveedor. */
    private double peso;

    private LocalDateTime fecha = LocalDateTime.now();

    public Similitud(Long cancionId, Long similarId, String fuente, double peso) {
        this.cancionId = cancionId;
        this.similarId = similarId;
        this.fuente = fuente;
        this.peso = peso;
    }
}
