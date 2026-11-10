package com.bpm.music.soporte;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/** Cuántas veces ocurrió una incidencia cada día (para los informes por día, semana y mes). */
@Entity
@Table(name = "soporte_incidencia_dia",
        uniqueConstraints = @UniqueConstraint(name = "uk_incdia", columnNames = {"incidencia_id", "dia"}),
        indexes = @Index(name = "idx_incdia_dia", columnList = "dia"))
@Getter
@Setter
@NoArgsConstructor
public class IncidenciaDia {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "incidencia_id", nullable = false)
    private Long incidenciaId;

    @Column(nullable = false)
    private LocalDate dia;

    @Column(length = 40)
    private String categoria;

    @Column(length = 10)
    private String severidad;

    private long cuenta;
}
