package com.bpm.music.models;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "usuario")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Usuario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String email;

    @Column(unique = true, nullable = false, length = 50)
    private String username;

    @Column(nullable = false)
    private String password;

    @Column(length = 100)
    private String nombreDisplay;

    @Column(length = 500)
    private String fotoPerfil;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private Rol rol = Rol.USER;

    @Builder.Default
    private boolean activo = true;

    @Builder.Default
    private boolean emailVerificado = false;

    // Token para recuperación de contraseña
    @Column(length = 200)
    private String resetToken;
    private LocalDateTime resetTokenExpiry;

    // Token para verificación de email
    @Column(length = 200)
    private String verificationToken;

    @Builder.Default
    private LocalDateTime fechaRegistro = LocalDateTime.now();

    private LocalDateTime ultimoLogin;

    // ── Solicitud de colaborador ──
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private EstadoSolicitud solicitudColaborador = EstadoSolicitud.NINGUNA;

    @Column(length = 500)
    private String motivoSolicitud;     // mensaje que el usuario escribe al solicitar

    private LocalDateTime fechaSolicitud;

    @Column(length = 200)
    private String respuestaAdmin;      // nota del admin al aprobar/rechazar

    // ─── Roles ───
    public enum Rol {
        USER,           // usuario normal
        COLABORADOR,    // puede subir LRC, subir canciones, editar nombres
        ADMIN           // acceso total + panel admin
    }

    // ─── Estados de solicitud ───
    public enum EstadoSolicitud {
        NINGUNA,    // nunca ha solicitado
        PENDIENTE,  // solicitud enviada, esperando respuesta
        APROBADA,   // aprobada → rol cambia a COLABORADOR
        RECHAZADA   // rechazada
    }
}
