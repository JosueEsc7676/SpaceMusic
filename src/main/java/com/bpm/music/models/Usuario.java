package com.bpm.music.models;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "usuario")
@Getter
@Setter
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

    /** Token para recuperar la contraseña. */
    @Column(length = 200)
    private String resetToken;
    private LocalDateTime resetTokenExpiry;

    /** Token para verificar el correo (registro o cambio de correo). */
    @Column(length = 200)
    private String verificationToken;

    /** Nuevo correo pendiente de confirmar. */
    @Column(length = 255)
    private String emailPendiente;

    @Builder.Default
    private LocalDateTime fechaRegistro = LocalDateTime.now();

    private LocalDateTime ultimoLogin;

    // ── Solicitud de colaborador ──
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private EstadoSolicitud solicitudColaborador = EstadoSolicitud.NINGUNA;

    @Column(length = 500)
    private String motivoSolicitud;

    private LocalDateTime fechaSolicitud;

    @Column(length = 200)
    private String respuestaAdmin;

    public String getNombreVisible() {
        return (nombreDisplay != null && !nombreDisplay.isBlank()) ? nombreDisplay : username;
    }

    public EstadoSolicitud getSolicitudColaborador() {
        return solicitudColaborador != null ? solicitudColaborador : EstadoSolicitud.NINGUNA;
    }

    public enum Rol {
        USER,           // usuario normal
        COLABORADOR,    // puede subir letras y canciones y editar datos
        ADMIN           // acceso total
    }

    public enum EstadoSolicitud {
        NINGUNA, PENDIENTE, APROBADA, RECHAZADA
    }
}
