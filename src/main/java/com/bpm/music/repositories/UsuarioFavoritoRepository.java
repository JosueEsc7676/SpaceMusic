package com.bpm.music.repositories;

import com.bpm.music.models.UsuarioFavorito;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UsuarioFavoritoRepository extends JpaRepository<UsuarioFavorito, Long> {

    List<UsuarioFavorito> findByUsuarioIdOrderByFechaAgregadoDesc(Long usuarioId);

    Optional<UsuarioFavorito> findByUsuarioIdAndCancionId(Long usuarioId, Long cancionId);

    boolean existsByUsuarioIdAndCancionId(Long usuarioId, Long cancionId);

    void deleteByUsuarioIdAndCancionId(Long usuarioId, Long cancionId);

    long countByUsuarioId(Long usuarioId);
}
