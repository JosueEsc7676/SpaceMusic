package com.bpm.music.experiencias;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface SesionCoincidenciaRepository extends JpaRepository<SesionCoincidencia, Long> {
    @Query("SELECT s FROM SesionCoincidencia s WHERE (s.creadorId = :u OR s.invitadoId = :u) AND s.estado IN :estados ORDER BY s.fecha DESC")
    List<SesionCoincidencia> deUsuario(@Param("u") Long usuarioId, @Param("estados") Collection<SesionCoincidencia.Estado> estados);
}
