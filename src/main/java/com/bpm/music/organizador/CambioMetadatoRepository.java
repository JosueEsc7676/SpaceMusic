package com.bpm.music.organizador;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface CambioMetadatoRepository extends JpaRepository<CambioMetadato, Long> {

    boolean existsByCancionIdAndCampoAndFuente(Long cancionId, String campo, String fuente);

    boolean existsByCancionIdAndCampoAndEstado(Long cancionId, String campo, CambioMetadato.Estado estado);

    @Query("SELECT COUNT(c) > 0 FROM CambioMetadato c WHERE c.cancionId = :id AND c.campo = :campo AND c.estado = 'SIN_RESULTADO' AND c.fecha > :desde")
    boolean sinResultadoReciente(@Param("id") Long cancionId, @Param("campo") String campo, @Param("desde") LocalDateTime desde);

    @Query("SELECT c.cancionId FROM CambioMetadato c WHERE c.campo = :campo AND c.fuente = 'manual'")
    List<Long> conCambioManual(@Param("campo") String campo);

    Page<CambioMetadato> findByEstadoInOrderByFechaDesc(Collection<CambioMetadato.Estado> estados, Pageable p);

    Page<CambioMetadato> findByEstadoInAndCampoOrderByFechaDesc(Collection<CambioMetadato.Estado> estados, String campo, Pageable p);

    long countByEstado(CambioMetadato.Estado estado);

    long countByEstadoAndCampo(CambioMetadato.Estado estado, String campo);
}
