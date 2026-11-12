package com.bpm.music.organizador;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface EtiquetaCancionRepository extends JpaRepository<EtiquetaCancion, Long> {

    @Query("SELECT DISTINCT e.cancionId FROM EtiquetaCancion e WHERE e.origen = :o")
    List<Long> conEtiquetaDe(@Param("o") String origen);

    Page<EtiquetaCancion> findByOrigenAndEstadoOrderByFechaDesc(String origen, EtiquetaCancion.Estado estado, Pageable p);

    List<EtiquetaCancion> findByTipoAndEstado(String tipo, EtiquetaCancion.Estado estado);

    long countByOrigenAndEstado(String origen, EtiquetaCancion.Estado estado);
}
