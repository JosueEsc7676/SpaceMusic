package com.bpm.music.organizador;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface CancionExternaRepository extends JpaRepository<CancionExterna, Long> {

    Optional<CancionExterna> findByProveedorAndCancionId(String proveedor, Long cancionId);

    List<CancionExterna> findByProveedorAndIdExternoAndEstado(String proveedor, String idExterno, CancionExterna.Estado estado);

    @Query("SELECT c.cancionId FROM CancionExterna c WHERE c.proveedor = :p")
    List<Long> cancionesConVinculo(@Param("p") String proveedor);

    @Query("SELECT c FROM CancionExterna c WHERE c.proveedor = :p AND c.estado = 'VERIFICADA' AND (c.relacionadasEn IS NULL OR c.relacionadasEn < :antes) ORDER BY c.relacionadasEn ASC NULLS FIRST, c.id ASC")
    List<CancionExterna> pendientesDeRelacionadas(@Param("p") String proveedor, @Param("antes") LocalDateTime antes, Pageable pagina);

    long countByProveedorAndEstado(String proveedor, CancionExterna.Estado estado);

    List<CancionExterna> findTop100ByProveedorAndEstadoOrderByFechaDesc(String proveedor, CancionExterna.Estado estado);
}
