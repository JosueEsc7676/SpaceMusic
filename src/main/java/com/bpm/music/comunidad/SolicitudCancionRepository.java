package com.bpm.music.comunidad;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface SolicitudCancionRepository extends JpaRepository<SolicitudCancion, Long> {

    List<SolicitudCancion> findTop50ByUsuarioIdOrderByFechaDesc(Long usuarioId);

    List<SolicitudCancion> findByEstado(SolicitudCancion.Estado estado);

    Page<SolicitudCancion> findByEstadoInOrderByFechaDesc(Collection<SolicitudCancion.Estado> estados, Pageable p);

    long countByEstado(SolicitudCancion.Estado estado);

    long countByUsuarioIdAndFechaAfter(Long usuarioId, LocalDateTime desde);
}
