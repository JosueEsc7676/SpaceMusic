package com.bpm.music.comunidad;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Gustos que la persona eligió al crear su cuenta (géneros y artistas). Ayudan a recomendar desde el primer día. */
@Entity
@Table(name = "usuario_gusto", indexes = @Index(columnList = "usuarioId"))
@Getter
@Setter
@NoArgsConstructor
public class UsuarioGusto {

    public enum Tipo { GENERO, ARTISTA, HECHO }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long usuarioId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Tipo tipo;

    @Column(nullable = false, length = 200)
    private String valor;

    private LocalDateTime fecha = LocalDateTime.now();

    public UsuarioGusto(Long usuarioId, Tipo tipo, String valor) {
        this.usuarioId = usuarioId;
        this.tipo = tipo;
        this.valor = valor;
    }
}
