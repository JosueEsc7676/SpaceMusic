package com.bpm.music.repositories;

import com.bpm.music.models.Cancion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface CancionRepository extends JpaRepository<Cancion, Long> {

    boolean existsByRutaArchivo(String rutaArchivo);

    Optional<Cancion> findByRutaArchivo(String rutaArchivo);

    Page<Cancion> findAll(Pageable pageable);

    // =============================================
    // BÚSQUEDA FULLTEXT (requiere índice FULLTEXT en MySQL)
    // CREATE FULLTEXT INDEX idx_ft_cancion ON cancion(nombre, artista, album);
    // =============================================
    @Query(value = """
        SELECT *,
               MATCH(nombre, artista, album) AGAINST(:q IN BOOLEAN MODE) AS score
        FROM cancion
        WHERE MATCH(nombre, artista, album) AGAINST(:q IN BOOLEAN MODE)
        ORDER BY score DESC
        LIMIT 50
    """, nativeQuery = true)
    List<Cancion> buscarFullText(@Param("q") String q);

    // =============================================
    // BÚSQUEDA CON LIKE — fallback sin índice especial
    // Ordenada por relevancia: nombre > artista > album
    // =============================================
    @Query(value = """
        SELECT * FROM cancion
        WHERE
            LOWER(nombre)  LIKE LOWER(CONCAT('%', :q, '%'))
            OR LOWER(artista) LIKE LOWER(CONCAT('%', :q, '%'))
            OR LOWER(album)   LIKE LOWER(CONCAT('%', :q, '%'))
        ORDER BY
            CASE
                WHEN LOWER(nombre)  LIKE LOWER(CONCAT(:q, '%')) THEN 1
                WHEN LOWER(artista) LIKE LOWER(CONCAT(:q, '%')) THEN 2
                WHEN LOWER(album)   LIKE LOWER(CONCAT(:q, '%')) THEN 3
                WHEN LOWER(nombre)  LIKE LOWER(CONCAT('%', :q, '%')) THEN 4
                WHEN LOWER(artista) LIKE LOWER(CONCAT('%', :q, '%')) THEN 5
                ELSE 6
            END,
            reproducciones DESC,
            nombre ASC
        LIMIT 50
    """, nativeQuery = true)
    List<Cancion> buscarOptimizadoLike(@Param("q") String q, Pageable pageable);

    // =============================================
    // BÚSQUEDA LEGACY (mantener por compatibilidad)
    // =============================================
    @Query("SELECT c FROM Cancion c WHERE " +
            "LOWER(c.nombre) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
            "LOWER(c.artista) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
            "LOWER(c.album) LIKE LOWER(CONCAT('%', :query, '%'))")
    List<Cancion> buscarOptimizado(@Param("query") String query, Pageable pageable);

    // =============================================
    // POR ARTISTA / ÁLBUM / GÉNERO
    // =============================================
    @Query("SELECT c FROM Cancion c WHERE LOWER(c.artista) LIKE LOWER(CONCAT('%', :artista, '%'))")
    List<Cancion> findByArtistaContainingIgnoreCase(@Param("artista") String artista);

    @Query("SELECT c FROM Cancion c WHERE LOWER(c.album) LIKE LOWER(CONCAT('%', :album, '%'))")
    List<Cancion> findByAlbumContainingIgnoreCase(@Param("album") String album);

    @Query("SELECT c FROM Cancion c WHERE c.album = :album")
    List<Cancion> findByAlbumExact(@Param("album") String album);

    @Query("SELECT c FROM Cancion c WHERE LOWER(c.genero) LIKE LOWER(CONCAT('%', :genero, '%'))")
    List<Cancion> findByGeneroIgnoreCase(@Param("genero") String genero);

    // =============================================
    // FAVORITOS / TOP
    // =============================================
    List<Cancion> findByFavoritoTrueOrderByFechaAgregadaDesc();

    List<Cancion> findTop20ByOrderByReproduccionesDesc();

    // =============================================
    // INCREMENTAR REPRODUCCIÓN — UPDATE directo (más rápido que load+save)
    // =============================================
    @Transactional
    @Modifying
    @Query("UPDATE Cancion c SET c.reproducciones = c.reproducciones + 1 WHERE c.id = :id")
    void incrementarReproduccion(@Param("id") Long id);

    // =============================================
    // ÁLBUMES
    // =============================================
    @Query("SELECT c.album, COUNT(c) FROM Cancion c WHERE c.album IS NOT NULL AND c.album != '' GROUP BY c.album ORDER BY c.album")
    List<Object[]> findAlbumesConConteo();

    @Query(value = """
        SELECT c.album, COUNT(*), MIN(c.id)
        FROM cancion c
        WHERE c.album IS NOT NULL AND c.album != ''
        GROUP BY c.album
        ORDER BY c.album ASC
    """, nativeQuery = true)
    List<Object[]> findAlbumesConId();

    // =============================================
    // ARTISTAS (para ArtistaService)
    // =============================================
    @Query(value = """
        SELECT
            c.artista,
            COUNT(*)                AS total_canciones,
            COUNT(DISTINCT c.album) AS total_albums,
            SUM(c.reproducciones)   AS total_repros,
            MIN(c.id)               AS primera_id
        FROM cancion c
        WHERE c.artista IS NOT NULL AND c.artista != ''
        GROUP BY c.artista
        ORDER BY total_repros DESC
    """, nativeQuery = true)
    List<Object[]> findArtistasConInfo();

    // =============================================
    // ESTADÍSTICAS
    // =============================================
    @Query(value = """
        SELECT
            COUNT(*)                           AS total_canciones,
            COUNT(DISTINCT artista)            AS total_artistas,
            COUNT(DISTINCT album)              AS total_albumes,
            COUNT(DISTINCT genero)             AS total_generos,
            COALESCE(SUM(reproducciones), 0)   AS total_reproducciones,
            COALESCE(SUM(duracion_segundos), 0) AS duracion_total,
            SUM(CASE WHEN favorito = 1 THEN 1 ELSE 0 END) AS total_favoritos
        FROM cancion
    """, nativeQuery = true)
    List<Object[]> findEstadisticasGenerales();

    @Query(value = """
        SELECT artista,
               SUM(reproducciones) AS repros,
               COUNT(*)            AS canciones
        FROM cancion
        WHERE artista IS NOT NULL AND artista != ''
        GROUP BY artista
        ORDER BY repros DESC
        LIMIT :limite
    """, nativeQuery = true)
    List<Object[]> findTopArtistasPorReproducciones(@Param("limite") int limite);

    @Query(value = """
        SELECT genero,
               SUM(reproducciones) AS repros,
               COUNT(*)            AS canciones
        FROM cancion
        WHERE genero IS NOT NULL AND genero != ''
        GROUP BY genero
        ORDER BY repros DESC
    """, nativeQuery = true)
    List<Object[]> findGenerosPorReproducciones();

    @Query(value = """
        SELECT YEAR(fecha_agregada)  AS anio,
               MONTH(fecha_agregada) AS mes,
               COUNT(*)              AS cantidad
        FROM cancion
        GROUP BY YEAR(fecha_agregada), MONTH(fecha_agregada)
        ORDER BY anio DESC, mes DESC
        LIMIT 12
    """, nativeQuery = true)
    List<Object[]> findCancionesPorMes();

    @Query(value = """
        SELECT duracion_segundos
        FROM cancion
        WHERE duracion_segundos > 0
    """, nativeQuery = true)
    List<Object[]> findDistribucionDuracion();

    // =============================================
    // BUSCAR LIGERO (compatibilidad con código anterior)
    // =============================================
    @Query(value = """
        SELECT * FROM cancion c
        WHERE
            LOWER(c.nombre)  LIKE LOWER(CONCAT('%', :q, '%'))
            OR LOWER(c.artista) LIKE LOWER(CONCAT('%', :q, '%'))
            OR LOWER(c.album)   LIKE LOWER(CONCAT('%', :q, '%'))
        ORDER BY
            CASE
                WHEN LOWER(c.nombre) LIKE LOWER(CONCAT(:q, '%')) THEN 1
                WHEN LOWER(c.artista) LIKE LOWER(CONCAT(:q, '%')) THEN 2
                ELSE 3
            END, c.nombre ASC
        LIMIT 50
    """, nativeQuery = true)
    List<Object[]> buscarLigero(@Param("q") String q, Pageable pageable);
}
