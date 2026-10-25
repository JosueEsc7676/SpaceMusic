package com.bpm.music.repositories;

import com.bpm.music.models.Playlist;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface PlaylistRepository extends JpaRepository<Playlist, Long> {

    List<Playlist> findByUsuarioIdOrderByFechaCreacionDesc(Long usuarioId);

    Optional<Playlist> findByIdAndUsuarioId(Long id, Long usuarioId);

    @Query("SELECT p FROM Playlist p WHERE p.publica = true AND (p.usuarioId IS NULL OR p.usuarioId <> :uid) ORDER BY p.fechaActualizacion DESC, p.fechaCreacion DESC")
    List<Playlist> findPublicasDeOtros(@Param("uid") Long uid);

    /** IDs de canciones de una playlist (sin cargar las entidades). */
    @Query(value = "SELECT cancion_id FROM playlist_cancion WHERE playlist_id = :pid", nativeQuery = true)
    List<Number> findCancionIds(@Param("pid") Long playlistId);

    @Query(value = "SELECT COUNT(*) FROM playlist_cancion WHERE playlist_id = :pid AND cancion_id = :cid", nativeQuery = true)
    long existeCancion(@Param("pid") Long playlistId, @Param("cid") Long cancionId);

    @Transactional
    @Modifying
    @Query(value = "INSERT INTO playlist_cancion (playlist_id, cancion_id) VALUES (:pid, :cid)", nativeQuery = true)
    void insertarCancion(@Param("pid") Long playlistId, @Param("cid") Long cancionId);

    @Transactional
    @Modifying
    @Query(value = "DELETE FROM playlist_cancion WHERE playlist_id = :pid AND cancion_id = :cid", nativeQuery = true)
    int quitarCancion(@Param("pid") Long playlistId, @Param("cid") Long cancionId);

    @Transactional
    @Modifying
    @Query(value = "DELETE FROM playlist_cancion WHERE playlist_id = :pid", nativeQuery = true)
    void vaciar(@Param("pid") Long playlistId);
}
