package com.bpm.music.repositories;

import com.bpm.music.dtos.CancionDTO;
import com.bpm.music.models.Cancion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface CancionRepository extends JpaRepository<Cancion, Long> {

    /** Todas las canciones como DTO ligero, sin cargar portadas ni letras. */
    @Query("""
        SELECT new com.bpm.music.dtos.CancionDTO(
            c.id, c.nombre, c.artista, c.album, c.genero,
            c.duracionSegundos, c.reproducciones,
            CASE WHEN c.portada IS NOT NULL THEN true ELSE false END,
            c.portadaHash, c.portadaUrl, c.formato, c.anio, c.numeroPista,
            CASE WHEN LENGTH(c.letraSincronizada) > 0 THEN true ELSE false END,
            CASE WHEN LENGTH(c.letra) > 0 THEN true ELSE false END,
            c.fechaAgregada)
        FROM Cancion c
        """)
    List<CancionDTO> findAllLigero();

    @Query("SELECT c.rutaArchivo FROM Cancion c")
    List<String> findAllRutas();

    @Query("SELECT c.id, c.rutaArchivo FROM Cancion c")
    List<Object[]> findAllIdsYRutas();

    @Query("SELECT c.rutaArchivo FROM Cancion c WHERE c.id = :id")
    Optional<String> findRutaById(@Param("id") Long id);

    /** [id, huella] de las canciones con portada (para preparar miniaturas). */
    @Query("SELECT c.id, c.portadaHash FROM Cancion c WHERE c.portada IS NOT NULL")
    List<Object[]> findIdsYHuellasConPortada();

    @Query("SELECT c.portada FROM Cancion c WHERE c.id = :id")
    Optional<byte[]> findPortadaById(@Param("id") Long id);

    @Query("SELECT c.letra, c.letraSincronizada FROM Cancion c WHERE c.id = :id")
    List<Object[]> findLetrasById(@Param("id") Long id);

    boolean existsByRutaArchivo(String rutaArchivo);

    Optional<Cancion> findFirstByRutaArchivo(String rutaArchivo);

    /** Pone el género a canciones que no lo tienen (se obtiene del artista). */
    @Transactional
    @Modifying
    @Query("UPDATE Cancion c SET c.genero = :genero WHERE c.id IN :ids AND (c.genero IS NULL OR c.genero = '' OR c.genero = 'Desconocido')")
    int ponerGenero(@Param("ids") java.util.Collection<Long> ids, @Param("genero") String genero);

    @Transactional
    @Modifying
    @Query("UPDATE Cancion c SET c.reproducciones = c.reproducciones + 1 WHERE c.id = :id")
    void incrementarReproduccion(@Param("id") Long id);

    @Transactional
    @Modifying
    @Query("UPDATE Cancion c SET c.letra = :letra, c.letraSincronizada = :lrc WHERE c.id = :id")
    int actualizarLetras(@Param("id") Long id, @Param("letra") String letra, @Param("lrc") String lrc);

    @Transactional
    @Modifying
    @Query("UPDATE Cancion c SET c.portada = :portada, c.portadaHash = :hash WHERE c.id = :id")
    int actualizarPortada(@Param("id") Long id, @Param("portada") byte[] portada, @Param("hash") String hash);

    // ── Limpieza de relaciones antes de borrar canciones huérfanas ──
    @Transactional
    @Modifying
    @Query(value = "DELETE FROM playlist_cancion WHERE cancion_id IN (:ids)", nativeQuery = true)
    void borrarDePlaylists(@Param("ids") List<Long> ids);

    @Transactional
    @Modifying
    @Query("DELETE FROM UsuarioFavorito f WHERE f.cancion.id IN (:ids)")
    void borrarFavoritos(@Param("ids") List<Long> ids);

    @Transactional
    @Modifying
    @Query("DELETE FROM UsuarioReproduccion r WHERE r.cancion.id IN (:ids)")
    void borrarReproducciones(@Param("ids") List<Long> ids);

    @Transactional
    @Modifying
    @Query("DELETE FROM HistorialReproduccion h WHERE h.cancion.id IN (:ids)")
    void borrarHistorial(@Param("ids") List<Long> ids);

    @Transactional
    @Modifying
    @Query("DELETE FROM Cancion c WHERE c.id IN (:ids)")
    void borrarPorIds(@Param("ids") List<Long> ids);

    // ── Estadísticas globales ──
    @Query(value = """
        SELECT YEAR(fecha_agregada) AS anio, MONTH(fecha_agregada) AS mes, COUNT(*) AS cantidad
        FROM cancion
        WHERE fecha_agregada IS NOT NULL
        GROUP BY YEAR(fecha_agregada), MONTH(fecha_agregada)
        ORDER BY anio DESC, mes DESC
        LIMIT 12
        """, nativeQuery = true)
    List<Object[]> findCancionesPorMes();
}
