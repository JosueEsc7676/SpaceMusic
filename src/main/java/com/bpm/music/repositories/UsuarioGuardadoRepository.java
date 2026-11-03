package com.bpm.music.repositories;

import com.bpm.music.models.UsuarioGuardado;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface UsuarioGuardadoRepository extends JpaRepository<UsuarioGuardado, Long> {

    List<UsuarioGuardado> findByUsuarioIdOrderByFechaDesc(Long usuarioId);

    boolean existsByUsuarioIdAndTipoAndClave(Long usuarioId, String tipo, String clave);

    @Modifying
    @Transactional
    long deleteByUsuarioIdAndTipoAndClave(Long usuarioId, String tipo, String clave);
}
