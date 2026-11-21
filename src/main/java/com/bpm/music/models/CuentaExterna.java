package com.bpm.music.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Cuenta de Google, Facebook o Apple enlazada a un usuario de SpaceMusic (para entrar con ese botón). */
@Entity
@Table(name = "cuenta_externa", uniqueConstraints = @UniqueConstraint(columnNames = {"proveedor", "sujeto"}))
@Getter
@Setter
@NoArgsConstructor
public class CuentaExterna {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 20) private String proveedor;
    /** Identificador de la persona en ese servicio («sub»). */
    @Column(nullable = false, length = 255) private String sujeto;
    @Column(name = "usuario_id", nullable = false) private Long usuarioId;
    private LocalDateTime fecha = LocalDateTime.now();

    public CuentaExterna(String proveedor, String sujeto, Long usuarioId) {
        this.proveedor = proveedor; this.sujeto = sujeto; this.usuarioId = usuarioId;
    }
}
