package com.bpm.music.models;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** Mensaje directo entre dos amigos (usuarios que se siguen mutuamente). */
@Entity
@Table(name = "mensaje", indexes = {
        @Index(name = "idx_msg_para", columnList = "para_id, leido"),
        @Index(name = "idx_msg_par", columnList = "de_id, para_id")})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Mensaje {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "de_id", nullable = false)
    private Long deId;

    @Column(name = "para_id", nullable = false)
    private Long paraId;

    /** Se guarda cifrado (AES-256-GCM): en la base de datos no se puede leer. Hasta 1000 caracteres de texto. */
    @Convert(converter = com.bpm.music.security.TextoCifrado.class)
    @Column(nullable = false, columnDefinition = "TEXT")
    private String texto;

    /** Canción compartida en el mensaje (opcional). */
    private Long cancionId;

    @Builder.Default
    private LocalDateTime fecha = LocalDateTime.now();

    @Builder.Default
    private boolean leido = false;

    /** null o TEXTO; IMAGEN o AUDIO cuando lleva un archivo (guardado cifrado en el servidor). */
    private String tipo;
    /** Nombre del archivo cifrado (en la carpeta «adjuntos»). */
    private String adjunto;
    private String adjuntoMime;
    /** Duración del audio en segundos. */
    private Integer duracion;
    /** Quien lo envió lo eliminó para todos. */
    private Boolean borrado;
    /** Quien lo recibió lo quitó de su conversación. */
    private Boolean ocultoPara;
}
