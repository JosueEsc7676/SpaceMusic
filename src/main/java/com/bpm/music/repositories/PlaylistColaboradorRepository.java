package com.bpm.music.repositories;

import com.bpm.music.models.PlaylistColaborador;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface PlaylistColaboradorRepository extends JpaRepository<PlaylistColaborador, Long> {

    List<PlaylistColaborador> findByPlaylistIdOrderByFechaAsc(Long playlistId);

    boolean existsByPlaylistIdAndUsuarioId(Long playlistId, Long usuarioId);

    long countByPlaylistId(Long playlistId);

    @Query("SELECT c.playlistId FROM PlaylistColaborador c WHERE c.usuarioId = :uid")
    List<Long> playlistsDe(@Param("uid") Long uid);

    @Transactional
    @Modifying
    @Query("DELETE FROM PlaylistColaborador c WHERE c.playlistId = :pid AND c.usuarioId = :uid")
    int quitar(@Param("pid") Long playlistId, @Param("uid") Long usuarioId);

    @Transactional
    @Modifying
    @Query("DELETE FROM PlaylistColaborador c WHERE c.playlistId = :pid")
    void quitarTodos(@Param("pid") Long playlistId);
}
