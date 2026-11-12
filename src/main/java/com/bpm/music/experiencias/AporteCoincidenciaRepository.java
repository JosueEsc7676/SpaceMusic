package com.bpm.music.experiencias;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

public interface AporteCoincidenciaRepository extends JpaRepository<AporteCoincidencia, Long> {
    List<AporteCoincidencia> findBySesionId(Long sesionId);
    @Transactional void deleteBySesionIdAndUsuarioIdAndCancionIdAndTipoIn(Long sesionId, Long usuarioId, Long cancionId, Collection<String> tipos);
    long countBySesionIdAndUsuarioIdAndTipo(Long sesionId, Long usuarioId, String tipo);
}
