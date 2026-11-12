package com.bpm.music.organizador;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Una ejecución del organizador. Su avance se guarda después de cada lote: tras un reinicio se retoma. */
@Entity
@Table(name = "trabajo_organizador", indexes = @Index(columnList = "estado"))
@Getter
@Setter
@NoArgsConstructor
public class TrabajoOrganizador {

    public enum Estado { EN_CURSO, PAUSADO, TERMINADO, CANCELADO, FALLIDO }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** diario | semanal | manual */
    @Column(nullable = false, length = 10)
    private String tipo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Estado estado;

    /** Etapa actual (índice en la lista de etapas). */
    private int etapa;

    /** Avance por etapa en JSON: {"vincular": 40, "letras": 12, …} y notas. */
    @Column(columnDefinition = "TEXT")
    private String avance;

    @Column(length = 300)
    private String mensaje;

    @Column(length = 60)
    private String iniciadoPor;

    private LocalDateTime inicio = LocalDateTime.now();
    private LocalDateTime fin;
    private LocalDateTime actualizado = LocalDateTime.now();
}
