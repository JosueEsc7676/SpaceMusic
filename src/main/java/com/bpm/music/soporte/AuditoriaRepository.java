package com.bpm.music.soporte;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface AuditoriaRepository extends JpaRepository<AuditoriaAdmin, Long> {

    @Query("SELECT a FROM AuditoriaAdmin a WHERE (:q IS NULL OR LOWER(a.accion) LIKE :q OR LOWER(a.detalle) LIKE :q OR LOWER(a.usuario) LIKE :q) "
            + "AND (:resultado IS NULL OR a.resultado = :resultado) AND (:desde IS NULL OR a.fecha >= :desde)")
    Page<AuditoriaAdmin> buscar(@Param("q") String q, @Param("resultado") String resultado,
                                @Param("desde") LocalDateTime desde, Pageable pageable);

    long countByFechaAfter(LocalDateTime fecha);

    long countByResultadoAndFechaAfter(String resultado, LocalDateTime fecha);
}
