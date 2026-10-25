package com.bpm.music.models;

import jakarta.persistence.*;
import lombok.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Entity
@Data
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
    private String rutaArchivo;
    private String portadaUrl;
    private int duracionSegundos;

    // 🔥 PORTADA EN BASE DE DATOS
    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(columnDefinition = "LONGBLOB")
    private byte[] portada;

    @Column(columnDefinition = "LONGTEXT")
    private String letra;

    @Column(columnDefinition = "LONGTEXT")
    private String letraSincronizada;

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