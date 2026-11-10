package com.bpm.music.soporte;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Alerta administrativa. Una misma condición (clave) no crea alertas repetidas: se actualiza la existente. */
@Entity
@Table(name = "soporte_alerta", indexes = {@Index(name = "idx_alerta_estado", columnList = "estado"),
        @Index(name = "idx_alerta_clave", columnList = "clave")})
@Getter
@Setter
@NoArgsConstructor
public class Alerta {

    public enum Estado { ACTIVA, RECONOCIDA, RESUELTA }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Condición que la produce, p. ej. "cpu", "db", "incidencia:42". */
    @Column(nullable = false, length = 80)
    private String clave;

    /** cpu, ram, disco, errores, db, almacenamiento, audio, incidencia, servicio */
    @Column(nullable = false, length = 30)
    private String tipo;

    /** critica, advertencia, info */
    @Column(length = 15)
    private String severidad;

    @Column(length = 400)
    private String mensaje;

    @Enumerated(EnumType.STRING)
    @Column(length = 12)
    private Estado estado = Estado.ACTIVA;

    private LocalDateTime creada;
    private LocalDateTime actualizada;
    private LocalDateTime resuelta;
    private long ocurrencias;

    @Column(length = 60)
    private String reconocidaPor;
    private LocalDateTime reconocidaEn;
    /** Última vez que se avisó a los administradores (para no repetir avisos). */
    private LocalDateTime avisada;
}
