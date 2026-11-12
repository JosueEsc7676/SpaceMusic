package com.bpm.music.experiencias;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface BloqueoDedicatoriaRepository extends JpaRepository<BloqueoDedicatoria, Long> {
    boolean existsByUsuarioIdAndBloqueadoId(Long usuarioId, Long bloqueadoId);
    List<BloqueoDedicatoria> findByUsuarioId(Long usuarioId);
    @Transactional void deleteByUsuarioIdAndBloqueadoId(Long usuarioId, Long bloqueadoId);
}
