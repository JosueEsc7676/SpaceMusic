package com.bpm.music.experiencias;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** "El punto donde coincidimos": dos personas que aceptaron participar. */
@Entity
@Table(name = "sesion_coincidencia", indexes = {@Index(columnList = "creadorId"), @Index(columnList = "invitadoId")})
@Getter
@Setter
@NoArgsConstructor
public class SesionCoincidencia {
    public enum Estado { INVITADA, ACTIVA, RECHAZADA, CERRADA }
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long creadorId;
    @Column(nullable = false) private Long invitadoId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) private Estado estado;
    private LocalDateTime fecha = LocalDateTime.now();
    private LocalDateTime aceptadaEn;
}
