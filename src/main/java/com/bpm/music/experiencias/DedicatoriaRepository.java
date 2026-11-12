package com.bpm.music.experiencias;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface DedicatoriaRepository extends JpaRepository<Dedicatoria, Long> {
    List<Dedicatoria> findTop100ByParaIdAndOcultaFalseOrderByFechaDesc(Long paraId);
    List<Dedicatoria> findTop100ByDeIdOrderByFechaDesc(Long deId);
    long countByDeIdAndFechaAfter(Long deId, LocalDateTime desde);
}
