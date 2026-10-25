package com.bpm.music.repositories;

import com.bpm.music.models.UsuarioFavorito;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface UsuarioFavoritoRepository extends JpaRepository<UsuarioFavorito, Long> {

    /** IDs de canciones favoritas, de la más reciente a la más antigua. */
    @Query("SELECT f.cancion.id FROM UsuarioFavorito f WHERE f.usuario.id = :uid ORDER BY f.fechaAgregado DESC")
    List<Long> findCancionIdsByUsuario(@Param("uid") Long usuarioId);

    boolean existsByUsuarioIdAndCancionId(Long usuarioId, Long cancionId);

    @Transactional
    @Modifying
    @Query("DELETE FROM UsuarioFavorito f WHERE f.usuario.id = :uid AND f.cancion.id = :cid")
    int borrar(@Param("uid") Long usuarioId, @Param("cid") Long cancionId);

    long countByUsuarioId(Long usuarioId);
}
