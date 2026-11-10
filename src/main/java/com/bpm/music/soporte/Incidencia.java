package com.bpm.music.soporte;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Incidencia técnica: errores o advertencias iguales agrupados por su huella.
 * El estado es solo el seguimiento administrativo (no indica que el error se haya reparado solo).
 */
@Entity
@Table(name = "soporte_incidencia",
        uniqueConstraints = @UniqueConstraint(name = "uk_incidencia_huella", columnNames = "huella"),
        indexes = {@Index(name = "idx_inc_ultima", columnList = "ultima"), @Index(name = "idx_inc_estado", columnList = "estado")})
@Getter
@Setter
@NoArgsConstructor
public class Incidencia {

    public enum Estado { NUEVA, EN_INVESTIGACION, RESUELTA, DESCARTADA }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String huella;

    /** ERROR o WARN */
    @Column(nullable = false, length = 10)
    private String severidad;

    /** Módulo: api, servicios, seguridad, base de datos, biblioteca, sistema… */
    @Column(length = 40)
    private String categoria;

    /** Clase que lo registró (nombre corto). */
    @Column(length = 120)
    private String origen;

    /** Tipo de excepción, si hubo. */
    @Column(length = 200)
    private String tipo;

    @Column(length = 600)
    private String mensaje;

    /** Ruta (patrón, sin identificadores) donde ocurrió, si fue durante una petición. */
    @Column(length = 140)
    private String endpoint;

    /** Último identificador de petición relacionado. */
    @Column(length = 20)
    private String requestId;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String traza;

    private LocalDateTime primera;
    private LocalDateTime ultima;
    private long repeticiones;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Estado estado = Estado.NUEVA;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String notas;

    private LocalDateTime actualizada;
    private LocalDateTime resueltaEn;
    /** Volvió a ocurrir después de marcarse como resuelta. */
    private Boolean reaparecio;
}
