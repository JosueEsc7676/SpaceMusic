package com.bpm.music.soporte;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface IncidenciaDiaRepository extends JpaRepository<IncidenciaDia, Long> {

    Optional<IncidenciaDia> findByIncidenciaIdAndDia(Long incidenciaId, LocalDate dia);

    /** [dia, severidad, ocurrencias] */
    @Query("SELECT d.dia, d.severidad, SUM(d.cuenta) FROM IncidenciaDia d WHERE d.dia >= :desde GROUP BY d.dia, d.severidad ORDER BY d.dia")
    List<Object[]> porDia(@Param("desde") LocalDate desde);

    /** [categoria, ocurrencias] */
    @Query("SELECT d.categoria, SUM(d.cuenta) FROM IncidenciaDia d WHERE d.dia >= :desde AND d.dia <= :hasta GROUP BY d.categoria")
    List<Object[]> porCategoria(@Param("desde") LocalDate desde, @Param("hasta") LocalDate hasta);

    @Query("SELECT COALESCE(SUM(d.cuenta), 0) FROM IncidenciaDia d WHERE d.dia >= :desde AND d.dia <= :hasta")
    long total(@Param("desde") LocalDate desde, @Param("hasta") LocalDate hasta);

    long deleteByDiaBefore(LocalDate dia);
}
