package com.bpm.music.organizador;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface SimilitudRepository extends JpaRepository<Similitud, Long> {

    @Transactional
    @Modifying
    @Query("DELETE FROM Similitud s WHERE s.cancionId = :id AND s.fuente = :f")
    void borrarDe(@Param("id") Long cancionId, @Param("f") String fuente);
}
