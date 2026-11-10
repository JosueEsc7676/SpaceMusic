package com.bpm.music.comunidad;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface UsuarioGustoRepository extends JpaRepository<UsuarioGusto, Long> {

    List<UsuarioGusto> findByUsuarioId(Long usuarioId);

    @Transactional
    @Modifying
    @Query("DELETE FROM UsuarioGusto g WHERE g.usuarioId = :uid")
    void borrarDe(@Param("uid") Long usuarioId);
}
