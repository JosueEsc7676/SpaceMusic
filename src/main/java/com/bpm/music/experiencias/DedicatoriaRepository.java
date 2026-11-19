package com.bpm.music.experiencias;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface DedicatoriaRepository extends JpaRepository<Dedicatoria, Long> {
    List<Dedicatoria> findTop100ByParaIdAndOcultaFalseOrderByFechaDesc(Long paraId);
    List<Dedicatoria> findTop100ByDeIdOrderByFechaDesc(Long deId);
    List<Dedicatoria> findByParaIdAndOcultaFalse(Long paraId);
    List<Dedicatoria> findByDeId(Long deId);
    long countByDeIdAndFechaAfter(Long deId, LocalDateTime desde);
    long countByDeIdAndEntregadaFalse(Long deId);
    /** Programadas cuya hora ya llegó. */
    List<Dedicatoria> findTop200ByEntregadaFalseAndProgramadaParaLessThanEqual(LocalDateTime ahora);
}
