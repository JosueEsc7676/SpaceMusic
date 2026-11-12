package com.bpm.music.organizador;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Vínculo de una canción local con su identificador en un proveedor externo (YouTube Music).
 * VERIFICADA: por la etiqueta del archivo o por una coincidencia exacta (título, artista, versión y duración).
 * AMBIGUA: casi coincide; queda para revisión y no se usa en recomendaciones.
 */
@Entity
@Table(name = "cancion_externa", uniqueConstraints = @UniqueConstraint(columnNames = {"proveedor", "cancionId"}),
        indexes = {@Index(columnList = "proveedor,idExterno"), @Index(columnList = "estado")})
@Getter
@Setter
@NoArgsConstructor
public class CancionExterna {

    public enum Estado { VERIFICADA, AMBIGUA, SIN_COINCIDENCIA, DESCARTADA }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long cancionId;

    @Column(nullable = false, length = 20)
    private String proveedor;

    @Column(length = 64)
    private String idExterno;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Estado estado;

    /** etiqueta-archivo | busqueda-exacta | relacionadas | manual */
    @Column(length = 30)
    private String metodo;

    @Column(length = 300)
    private String detalle;

    private LocalDateTime fecha = LocalDateTime.now();

    /** Última vez que se pidieron sus canciones relacionadas. */
    private LocalDateTime relacionadasEn;
}
