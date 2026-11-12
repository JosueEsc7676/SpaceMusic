package com.bpm.music.organizador;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Historial de cambios de datos de canciones: lo que completó un proveedor (LRCLIB, MusicBrainz,
 * Cover Art Archive), lo que propuso la IA y las correcciones manuales. Permite revisar, aceptar y revertir.
 * Las correcciones manuales (fuente "manual") nunca se sobrescriben automáticamente.
 */
@Entity
@Table(name = "cambio_metadato", indexes = {@Index(columnList = "cancionId,campo"), @Index(columnList = "estado")})
@Getter
@Setter
@NoArgsConstructor
public class CambioMetadato {

    public enum Estado { PENDIENTE, APLICADO, REVERTIDO, RECHAZADO, SIN_RESULTADO }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long cancionId;

    /** letra | portada | anio | album | artista | nombre | genero | etiqueta | duplicado */
    @Column(nullable = false, length = 20)
    private String campo;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String valorAnterior;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String valorNuevo;

    /** LRCLIB | MusicBrainz | Cover Art Archive | IA local | manual | sistema */
    @Column(nullable = false, length = 30)
    private String fuente;

    /** Cómo se encontró: "artista+título+álbum+duración exacta", "búsqueda aproximada (±2 s)", modelo… */
    @Column(length = 200)
    private String metodo;

    /** Explicación breve para la revisión (sin datos sensibles). */
    @Column(length = 500)
    private String detalle;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private Estado estado;

    private LocalDateTime fecha = LocalDateTime.now();
    private LocalDateTime resueltoEn;

    @Column(length = 60)
    private String resueltoPor;
}
