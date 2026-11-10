package com.bpm.music.soporte;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Estado del servidor tomado cada minuto (solo datos medidos; si algo no se pudo medir queda en null). */
@Entity
@Table(name = "soporte_metrica", indexes = @Index(name = "idx_met_fecha", columnList = "fecha"))
@Getter
@Setter
@NoArgsConstructor
public class MetricaMuestra {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDateTime fecha;

    private Long memoriaUsadaMb;
    private Long memoriaMaxMb;
    /** % de CPU del proceso y del sistema (null si la plataforma no lo informa). */
    private Double cpuProceso;
    private Double cpuSistema;
    private Double discoLibreGb;
    private Double discoTotalGb;

    /** En el último minuto */
    private Long solicitudes;
    private Long errores4xx;
    private Long errores5xx;
    private Double latenciaMediaMs;
    private Double latenciaP95Ms;
    private Long fallosAudio;
    private Long fallosLogin;

    private Boolean dbOk;
    private Double dbLatenciaMs;
    private Integer dbConexionesActivas;
    private Integer conexionesEnVivo;
    private Integer hilos;
    private Boolean almacenamientoOk;
}
