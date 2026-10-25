package com.bpm.music.repositories;

import com.bpm.music.models.Playlist;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PlaylistRepository extends JpaRepository<Playlist, Long> {

    // ✅ Solo playlists del usuario — cada quien ve las suyas
    @Query("SELECT DISTINCT p FROM Playlist p LEFT JOIN FETCH p.canciones WHERE p.usuarioId = :uid")
    List<Playlist> findByUsuarioIdWithCanciones(@Param("uid") Long uid);

    // Buscar playlist por id verificando que pertenece al usuario
    Optional<Playlist> findByIdAndUsuarioId(Long id, Long usuarioId);

    // Para admin: ver todas
    @Query("SELECT DISTINCT p FROM Playlist p LEFT JOIN FETCH p.canciones")
    List<Playlist> findAllWithCanciones();
}
