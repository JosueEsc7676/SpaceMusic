package com.bpm.music.models;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** Géneros de un artista obtenidos de MusicBrainz (los archivos descargados no traen género). */
@Entity
@Table(name = "artista_genero", uniqueConstraints = @UniqueConstraint(columnNames = "clave"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ArtistaGenero {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Nombre normalizado del artista. */
    @Column(nullable = false, length = 200)
    private String clave;

    @Column(length = 200)
    private String nombre;

    /** Géneros en orden de relevancia, separados por comas (en minúsculas, como los da MusicBrainz). */
    @Column(length = 600)
    private String generos;

    /** Género principal ya traducido para mostrar ("Reggaetón", "Hip hop"…). Vacío si no se encontró. */
    @Column(length = 100)
    private String principal;

    @Builder.Default
    private LocalDateTime fecha = LocalDateTime.now();
}
