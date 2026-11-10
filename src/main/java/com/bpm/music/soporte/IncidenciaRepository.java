package com.bpm.music.soporte;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface IncidenciaRepository extends JpaRepository<Incidencia, Long> {

    Optional<Incidencia> findByHuella(String huella);

    /** Listado con filtros (todos opcionales). La búsqueda de texto usa LIKE sobre mensaje, origen, tipo y ruta. */
    @Query("SELECT i FROM Incidencia i WHERE "
            + "(:q IS NULL OR LOWER(i.mensaje) LIKE :q OR LOWER(i.origen) LIKE :q OR LOWER(i.tipo) LIKE :q OR LOWER(i.endpoint) LIKE :q) "
            + "AND (:severidad IS NULL OR i.severidad = :severidad) "
            + "AND (:categoria IS NULL OR i.categoria = :categoria) "
            + "AND (:estado IS NULL OR i.estado = :estado) "
            + "AND (:endpoint IS NULL OR i.endpoint = :endpoint) "
            + "AND (:desde IS NULL OR i.ultima >= :desde) "
            + "AND (:hasta IS NULL OR i.ultima <= :hasta)")
    Page<Incidencia> buscar(@Param("q") String q, @Param("severidad") String severidad, @Param("categoria") String categoria,
                            @Param("estado") Incidencia.Estado estado, @Param("endpoint") String endpoint,
                            @Param("desde") LocalDateTime desde, @Param("hasta") LocalDateTime hasta, Pageable pageable);

    long countByEstado(Incidencia.Estado estado);

    @Query("SELECT i.categoria, COUNT(i), SUM(i.repeticiones) FROM Incidencia i GROUP BY i.categoria")
    List<Object[]> resumenPorCategoria();

    @Query("SELECT DISTINCT i.categoria FROM Incidencia i WHERE i.categoria IS NOT NULL")
    List<String> categorias();

    @Query("SELECT i FROM Incidencia i WHERE i.resueltaEn IS NOT NULL AND i.primera IS NOT NULL AND i.resueltaEn >= :desde")
    List<Incidencia> resueltasDesde(@Param("desde") LocalDateTime desde);
}
