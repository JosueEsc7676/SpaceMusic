package com.bpm.music.repositories;

import com.bpm.music.models.Mensaje;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface MensajeRepository extends JpaRepository<Mensaje, Long> {

    /** Mensajes entre dos personas, del más nuevo al más viejo (antes de un id, para cargar más). */
    @Query("SELECT m FROM Mensaje m WHERE ((m.deId = :a AND m.paraId = :b) OR (m.deId = :b AND m.paraId = :a)) AND m.id < :antes ORDER BY m.id DESC")
    List<Mensaje> conversacion(@Param("a") Long a, @Param("b") Long b, @Param("antes") Long antes, Pageable page);

    /** Últimos mensajes en los que participa el usuario (para armar la lista de conversaciones). */
    @Query("SELECT m FROM Mensaje m WHERE m.deId = :u OR m.paraId = :u ORDER BY m.id DESC")
    List<Mensaje> recientes(@Param("u") Long u, Pageable page);

    long countByParaIdAndLeidoFalse(Long paraId);

    @Query("SELECT m.deId, COUNT(m) FROM Mensaje m WHERE m.paraId = :u AND m.leido = false GROUP BY m.deId")
    List<Object[]> noLeidosPorRemitente(@Param("u") Long u);

    /** El mensaje sin leer más reciente (para la notificación). */
    Mensaje findFirstByParaIdAndLeidoFalseOrderByIdDesc(Long paraId);

    @Modifying
    @Transactional
    @Query("UPDATE Mensaje m SET m.leido = true WHERE m.paraId = :yo AND m.deId = :de AND m.leido = false")
    int marcarLeidos(@Param("yo") Long yo, @Param("de") Long de);
}
