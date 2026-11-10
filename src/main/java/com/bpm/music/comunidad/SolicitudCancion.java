package com.bpm.music.comunidad;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** "Pedir una canción": llega al panel de administración y se avisa a la persona cuando se agrega. */
@Entity
@Table(name = "solicitud_cancion", indexes = {@Index(columnList = "usuarioId"), @Index(columnList = "estado")})
@Getter
@Setter
@NoArgsConstructor
public class SolicitudCancion {

    public enum Estado { PENDIENTE, AGREGADA, RECHAZADA }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long usuarioId;

    @Column(nullable = false, length = 200)
    private String titulo;

    @Column(length = 200)
    private String artista;

    @Column(length = 500)
    private String nota;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Estado estado = Estado.PENDIENTE;

    /** Canción de la biblioteca que la cumple (cuando se agrega). */
    private Long cancionId;

    @Column(length = 300)
    private String respuesta;

    private LocalDateTime fecha = LocalDateTime.now();

    private LocalDateTime resuelta;
}
