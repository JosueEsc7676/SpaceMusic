package com.bpm.music.soporte;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface MetricaRepository extends JpaRepository<MetricaMuestra, Long> {

    List<MetricaMuestra> findByFechaAfterOrderByFechaAsc(LocalDateTime desde);

    List<MetricaMuestra> findByFechaBetweenOrderByFechaAsc(LocalDateTime desde, LocalDateTime hasta);

    @Transactional
    @Modifying
    @Query("DELETE FROM MetricaMuestra m WHERE m.fecha < :antes")
    int borrarAnteriores(@Param("antes") LocalDateTime antes);

    MetricaMuestra findFirstByOrderByFechaAsc();
}
