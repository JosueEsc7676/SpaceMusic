package com.bpm.music.repositories;

import com.bpm.music.models.Seguimiento;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface SeguimientoRepository extends JpaRepository<Seguimiento, Long> {

    boolean existsBySeguidorIdAndSeguidoId(Long seguidorId, Long seguidoId);

    long countBySeguidorId(Long seguidorId);

    long countBySeguidoId(Long seguidoId);

    @Query("SELECT s.seguidoId FROM Seguimiento s WHERE s.seguidorId = :uid ORDER BY s.fecha DESC")
    List<Long> siguiendo(@Param("uid") Long uid);

    @Query("SELECT s.seguidorId FROM Seguimiento s WHERE s.seguidoId = :uid ORDER BY s.fecha DESC")
    List<Long> seguidores(@Param("uid") Long uid);

    @Modifying
    @Transactional
    long deleteBySeguidorIdAndSeguidoId(Long seguidorId, Long seguidoId);
}
