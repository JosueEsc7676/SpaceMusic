package com.bpm.music.experiencias;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * "Hoy no" (una canción, por unos días) o "No me gusta este estilo" (un estilo, por más tiempo).
 * Son distintos: lo primero no dice nada del gusto; lo segundo baja ese estilo en Descubrir.
 */
@Entity
@Table(name = "descarte_descubrir", indexes = @Index(columnList = "usuarioId"))
@Getter
@Setter
@NoArgsConstructor
public class DescarteDescubrir {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long usuarioId;
    private Long cancionId;
    @Column(length = 20) private String estilo;
    @Column(nullable = false) private LocalDateTime hasta;
}
