package com.bpm.music.comunidad;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** "Reportar un problema" desde el menú de una canción (audio, letra, datos, portada…). */
@Entity
@Table(name = "reporte_cancion", indexes = {@Index(columnList = "cancionId"), @Index(columnList = "estado")})
@Getter
@Setter
@NoArgsConstructor
public class ReporteCancion {

    public enum Estado { ABIERTO, RESUELTO, DESCARTADO }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long cancionId;

    @Column(nullable = false)
    private Long usuarioId;

    /** audio | letra | datos | portada | duplicada | otro */
    @Column(nullable = false, length = 20)
    private String motivo;

    @Column(length = 500)
    private String detalle;

    /** Dispositivo desde el que se reportó (web, android, iphone, tv) */
    @Column(length = 20)
    private String cliente;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Estado estado = Estado.ABIERTO;

    @Column(length = 300)
    private String respuesta;

    private LocalDateTime fecha = LocalDateTime.now();

    private LocalDateTime resuelto;
}
