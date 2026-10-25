// repositories/HistorialReproduccionRepository.java
package com.bpm.music.repositories;

import com.bpm.music.models.HistorialReproduccion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface HistorialReproduccionRepository extends JpaRepository<HistorialReproduccion, Long> {

    /**
     * IDs de canciones reproducidas recientemente en esta sesión.
     * Usado para excluirlas de recomendaciones.
     */
    @Query(value = """
        SELECT h.cancion_id
        FROM historial_reproduccion h
        WHERE h.sesion_id = :sesionId
        ORDER BY h.fecha_reproduccion DESC
        LIMIT :limite
    """, nativeQuery = true)
    List<Number> findCancionIdsRecientes(@Param("sesionId") String sesionId,
                                       @Param("limite") int limite);

    /**
     * Artistas reproducidos recientemente — para evitar artista consecutivo.
     */
    @Query(value = """
        SELECT c.artista
        FROM historial_reproduccion h
        JOIN cancion c ON c.id = h.cancion_id
        WHERE h.sesion_id = :sesionId
        ORDER BY h.fecha_reproduccion DESC
        LIMIT :limite
    """, nativeQuery = true)
    List<String> findArtistasRecientes(@Param("sesionId") String sesionId,
                                        @Param("limite") int limite);

    /** Número de veces que se reprodujo una canción en una sesión. */
    @Query(value = """
        SELECT COUNT(*) FROM historial_reproduccion
        WHERE sesion_id = :sesionId AND cancion_id = :cancionId
    """, nativeQuery = true)
    int countBySesionAndCancion(@Param("sesionId") String sesionId,
                                 @Param("cancionId") Long cancionId);

    /** Limpieza automática de historial > 7 días (llamar en scheduled task). */
    @Transactional
    @Modifying
    @Query("DELETE FROM HistorialReproduccion h WHERE h.fechaReproduccion < :antes")
    void deleteOlderThan(@Param("antes") LocalDateTime antes);

    /** Conteo de sesión — para estadísticas de la sesión actual. */
    long countBySesionId(String sesionId);
}
