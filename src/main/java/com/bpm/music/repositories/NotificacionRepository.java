package com.bpm.music.repositories;

import com.bpm.music.models.Notificacion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface NotificacionRepository extends JpaRepository<Notificacion, Long> {

    List<Notificacion> findTop100ByUsuarioIdOrderByIdDesc(Long usuarioId);

    long countByUsuarioIdAndLeidaFalse(Long usuarioId);

    boolean existsByUsuarioIdAndTipoAndRuta(Long usuarioId, String tipo, String ruta);

    @Modifying
    @Transactional
    long deleteByUsuarioId(Long usuarioId);

    @Modifying
    @Transactional
    long deleteByIdAndUsuarioId(Long id, Long usuarioId);

    Notificacion findFirstByUsuarioIdAndLeidaFalseOrderByIdDesc(Long usuarioId);

    @Modifying
    @Transactional
    @Query("UPDATE Notificacion n SET n.leida = true WHERE n.usuarioId = :u AND n.leida = false")
    int marcarLeidas(@Param("u") Long u);
}
