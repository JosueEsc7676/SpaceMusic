package com.bpm.music.repositories;

import com.bpm.music.models.UsuarioReproduccion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface UsuarioReproduccionRepository extends JpaRepository<UsuarioReproduccion, Long> {

    long countByUsuarioId(Long usuarioId);

    long countByFechaAfter(LocalDateTime fecha);

    @Query("SELECT COUNT(DISTINCT r.usuario.id) FROM UsuarioReproduccion r WHERE r.fecha > :desde")
    long countUsuariosActivosDesde(@Param("desde") LocalDateTime desde);

    /** Últimas reproducciones del usuario: [cancionId, fecha]. */
    @Query("SELECT r.cancion.id, r.fecha FROM UsuarioReproduccion r WHERE r.usuario.id = :uid ORDER BY r.fecha DESC")
    List<Object[]> findRecientes(@Param("uid") Long uid, org.springframework.data.domain.Pageable pageable);

    /** Conteo de reproducciones por canción del usuario: [cancionId, veces]. */
    @Query("SELECT r.cancion.id, COUNT(r) FROM UsuarioReproduccion r WHERE r.usuario.id = :uid AND r.fecha >= :desde GROUP BY r.cancion.id ORDER BY COUNT(r) DESC")
    List<Object[]> findConteoPorCancion(@Param("uid") Long uid, @Param("desde") LocalDateTime desde);

    /** Segundos escuchados (usa la duración si el registro es antiguo). */
    @Query("SELECT COALESCE(SUM(COALESCE(r.segundos, r.cancion.duracionSegundos)), 0) FROM UsuarioReproduccion r WHERE r.usuario.id = :uid AND r.fecha >= :desde")
    Long sumSegundos(@Param("uid") Long uid, @Param("desde") LocalDateTime desde);

    /** Reproducciones por mes: [anio, mes, cantidad]. */
    @Query("SELECT YEAR(r.fecha), MONTH(r.fecha), COUNT(r) FROM UsuarioReproduccion r WHERE r.usuario.id = :uid GROUP BY YEAR(r.fecha), MONTH(r.fecha) ORDER BY YEAR(r.fecha) DESC, MONTH(r.fecha) DESC")
    List<Object[]> findPorMes(@Param("uid") Long uid);

    /** Fechas de reproducción del usuario desde una fecha (para hora del día y racha). */
    @Query("SELECT r.fecha FROM UsuarioReproduccion r WHERE r.usuario.id = :uid AND r.fecha >= :desde")
    List<LocalDateTime> findFechas(@Param("uid") Long uid, @Param("desde") LocalDateTime desde);

    /** Reproducciones globales recientes por canción: [cancionId, veces]. */
    @Query("SELECT r.cancion.id, COUNT(r) FROM UsuarioReproduccion r WHERE r.fecha >= :desde GROUP BY r.cancion.id ORDER BY COUNT(r) DESC")
    List<Object[]> findTendencias(@Param("desde") LocalDateTime desde, org.springframework.data.domain.Pageable pageable);

    /** Reproducciones de todos los usuarios por canción: [usuarioId, cancionId, veces] (para "quienes escuchan X también escuchan Y"). */
    @Query("SELECT r.usuario.id, r.cancion.id, COUNT(r) FROM UsuarioReproduccion r WHERE r.fecha >= :desde GROUP BY r.usuario.id, r.cancion.id")
    List<Object[]> findConteoGlobal(@Param("desde") LocalDateTime desde);

    /** Última reproducción del usuario: [cancionId, fecha]. */
    @Query("SELECT r.cancion.id, r.fecha FROM UsuarioReproduccion r WHERE r.usuario.id = :uid ORDER BY r.fecha DESC")
    List<Object[]> findUltimas(@Param("uid") Long uid, org.springframework.data.domain.Pageable pageable);
}
