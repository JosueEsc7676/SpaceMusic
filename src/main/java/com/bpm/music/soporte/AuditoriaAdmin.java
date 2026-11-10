package com.bpm.music.soporte;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Registro de una acción administrativa: quién, qué, cuándo y con qué resultado.
 * Solo se insertan filas (no hay endpoints para editar ni borrar la auditoría).
 */
@Entity
@Table(name = "soporte_auditoria", indexes = {@Index(name = "idx_aud_fecha", columnList = "fecha"),
        @Index(name = "idx_aud_accion", columnList = "accion")})
@Getter
@Setter
@NoArgsConstructor
public class AuditoriaAdmin {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private LocalDateTime fecha = LocalDateTime.now();

    @Column(updatable = false)
    private Long usuarioId;

    @Column(length = 60, updatable = false)
    private String usuario;

    /** Tipo de acción, p. ej. "rol.cambiar", "diagnostico.ejecutar", "POST /api/admin/aviso". */
    @Column(nullable = false, length = 80, updatable = false)
    private String accion;

    @Column(length = 600, updatable = false)
    private String detalle;

    /** OK, ERROR o DENEGADA */
    @Column(length = 12, updatable = false)
    private String resultado;

    @Column(length = 50, updatable = false)
    private String ip;
}
