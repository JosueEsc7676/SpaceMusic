package com.bpm.music.soporte;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Ajuste del soporte (umbrales de alertas, retención…). Cada cambio queda en la auditoría. */
@Entity
@Table(name = "soporte_config")
@Getter
@Setter
@NoArgsConstructor
public class ConfigSoporte {

    @Id
    @Column(length = 60)
    private String clave;

    @Column(length = 120)
    private String valor;

    private LocalDateTime actualizada;

    @Column(length = 60)
    private String actualizadaPor;
}
