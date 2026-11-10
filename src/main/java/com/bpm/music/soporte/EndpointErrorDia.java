package com.bpm.music.soporte;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/** Respuestas con error por ruta (sin identificadores) y día. */
@Entity
@Table(name = "soporte_endpoint_dia",
        uniqueConstraints = @UniqueConstraint(name = "uk_epdia", columnNames = {"dia", "endpoint", "estado_http"}),
        indexes = @Index(name = "idx_epdia_dia", columnList = "dia"))
@Getter
@Setter
@NoArgsConstructor
public class EndpointErrorDia {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate dia;

    @Column(nullable = false, length = 140)
    private String endpoint;

    @Column(name = "estado_http", nullable = false)
    private int estadoHttp;

    private long cuenta;
}
