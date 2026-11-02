package com.bpm.music.models;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "cancion", indexes = {
        @Index(name = "idx_cancion_artista", columnList = "artista"),
        @Index(name = "idx_cancion_album", columnList = "album"),
        @Index(name = "idx_cancion_genero", columnList = "genero")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Cancion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String nombre;

    @Column(nullable = false)
    private String artista;

    private String album;
    private String genero;

    /** Ruta absoluta del archivo en el servidor. Nunca se envía al navegador. */
    @Column(length = 1000)
    @JsonIgnore
    private String rutaArchivo;

    /** URL externa de portada (si no hay portada embebida). */
    private String portadaUrl;

    private int duracionSegundos;

    /** Número de pista dentro del álbum (de las etiquetas del archivo). */
    private Integer numeroPista;

    /** Año de lanzamiento (de las etiquetas del archivo). */
    private Integer anio;

    /** Extensión del archivo: mp3, flac, m4a, ogg, wav. */
    @Column(length = 10)
    private String formato;

    /** Bitrate en kbps. */
    private Integer bitrate;

    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(columnDefinition = "LONGBLOB")
    @JsonIgnore
    private byte[] portada;

    /** Huella del contenido de la portada: cambia si cambia la imagen (evita cachés viejas). */
    @Column(length = 16)
    private String portadaHash;

    /** Cambia la portada y actualiza su huella. */
    public void ponerPortada(byte[] datos) {
        this.portada = datos;
        this.portadaHash = com.bpm.music.services.ImagenService.huella(datos);
    }

    @Column(columnDefinition = "LONGTEXT")
    private String letra;

    @Column(columnDefinition = "LONGTEXT")
    private String letraSincronizada;

    /** Campo heredado (favoritos globales). Los favoritos ahora son por usuario. */
    @Builder.Default
    private boolean favorito = false;

    @Builder.Default
    private int reproducciones = 0;

    @Builder.Default
    private LocalDateTime fechaAgregada = LocalDateTime.now();

    @ManyToMany(mappedBy = "canciones", fetch = FetchType.LAZY)
    @JsonIgnore
    @Builder.Default
    private Set<Playlist> playlists = new HashSet<>();
}
