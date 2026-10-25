package com.bpm.music.repositories;

import com.bpm.music.models.UsuarioReproduccion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface UsuarioReproduccionRepository extends JpaRepository<UsuarioReproduccion, Long> {

    long countByUsuarioId(Long usuarioId);

    // Top canciones del usuario
    @Query(value = """
        SELECT c.id, c.nombre, c.artista, c.album, COUNT(*) as veces
        FROM usuario_reproduccion ur
        JOIN cancion c ON ur.cancion_id = c.id
        WHERE ur.usuario_id = :uid
        GROUP BY c.id, c.nombre, c.artista, c.album
        ORDER BY veces DESC
        LIMIT :limite
    """, nativeQuery = true)
    List<Object[]> findTopCancionesUsuario(@Param("uid") Long uid, @Param("limite") int limite);

    // Top artistas del usuario
    @Query(value = """
        SELECT c.artista, COUNT(*) as veces
        FROM usuario_reproduccion ur
        JOIN cancion c ON ur.cancion_id = c.id
        WHERE ur.usuario_id = :uid
        GROUP BY c.artista
        ORDER BY veces DESC
        LIMIT :limite
    """, nativeQuery = true)
    List<Object[]> findTopArtistasUsuario(@Param("uid") Long uid, @Param("limite") int limite);

    // Top géneros del usuario
    @Query(value = """
        SELECT c.genero, COUNT(*) as veces
        FROM usuario_reproduccion ur
        JOIN cancion c ON ur.cancion_id = c.id
        WHERE ur.usuario_id = :uid AND c.genero IS NOT NULL AND c.genero != ''
        GROUP BY c.genero
        ORDER BY veces DESC
        LIMIT 8
    """, nativeQuery = true)
    List<Object[]> findTopGenerosUsuario(@Param("uid") Long uid);

    // Reproducciones por mes
    @Query(value = """
        SELECT YEAR(ur.fecha) as anio, MONTH(ur.fecha) as mes, COUNT(*) as cantidad
        FROM usuario_reproduccion ur
        WHERE ur.usuario_id = :uid
        GROUP BY YEAR(ur.fecha), MONTH(ur.fecha)
        ORDER BY anio DESC, mes DESC
        LIMIT 12
    """, nativeQuery = true)
    List<Object[]> findReproduccionesPorMes(@Param("uid") Long uid);

    // Minutos escuchados total
    @Query(value = """
        SELECT COALESCE(SUM(c.duracion_segundos), 0)
        FROM usuario_reproduccion ur
        JOIN cancion c ON ur.cancion_id = c.id
        WHERE ur.usuario_id = :uid
    """, nativeQuery = true)
    Long findTotalSegundosEscuchados(@Param("uid") Long uid);
}
