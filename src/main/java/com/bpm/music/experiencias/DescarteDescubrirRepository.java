package com.bpm.music.experiencias;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface DescarteDescubrirRepository extends JpaRepository<DescarteDescubrir, Long> {
    List<DescarteDescubrir> findByUsuarioIdAndHastaAfter(Long usuarioId, LocalDateTime ahora);
}
