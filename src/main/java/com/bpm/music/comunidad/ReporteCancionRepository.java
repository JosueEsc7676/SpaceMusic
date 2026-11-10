package com.bpm.music.comunidad;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Collection;

public interface ReporteCancionRepository extends JpaRepository<ReporteCancion, Long> {

    Page<ReporteCancion> findByEstadoInOrderByFechaDesc(Collection<ReporteCancion.Estado> estados, Pageable p);

    long countByEstado(ReporteCancion.Estado estado);

    long countByUsuarioIdAndFechaAfter(Long usuarioId, LocalDateTime desde);

    boolean existsByCancionIdAndUsuarioIdAndMotivoAndEstado(Long cancionId, Long usuarioId, String motivo, ReporteCancion.Estado estado);
}
