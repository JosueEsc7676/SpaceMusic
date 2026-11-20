package com.bpm.music.repositories;

import com.bpm.music.models.BloqueoMensajes;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface BloqueoMensajesRepository extends JpaRepository<BloqueoMensajes, Long> {
    boolean existsByUsuarioIdAndBloqueadoId(Long usuarioId, Long bloqueadoId);
    List<BloqueoMensajes> findByUsuarioId(Long usuarioId);
    @Transactional
    void deleteByUsuarioIdAndBloqueadoId(Long usuarioId, Long bloqueadoId);
}
